package pipeline.simple

import chisel3._
import chisel3.util._
import common.BaseStage
import control.enums.ExceptionPos
import memory.bundles.{CacheMaintenanceControlNdPort, CacheMaintenanceHandshakePort, MemRequestHandshakePort}
import pipeline.common.bundles.{CacheMaintenanceInstNdPort, MemRequestNdPort}
import pipeline.common.enums.CacheMaintenanceTargetType
import pipeline.simple.bundles.WbNdPort
import spec.Param.isFullUncachedPatch
import spec._

class MemReqNdPort extends Bundle {
  val isAtomicStore           = new Bool()
  val isAtomicStoreSuccessful = new Bool()
  val translatedMemReq        = new MemRequestNdPort
  val isCached                = Bool()
  val cacheMaintenance        = new CacheMaintenanceInstNdPort
  val wb                      = new WbNdPort
}

object MemReqNdPort {
  def default: MemReqNdPort = 0.U.asTypeOf(new MemReqNdPort)
}

class MemReqPeerPort extends Bundle {
  val dCacheReq         = Flipped(new MemRequestHandshakePort)
  val uncachedReq       = Flipped(new MemRequestHandshakePort)
  val dCacheMaintenance = Flipped(new CacheMaintenanceHandshakePort)
  val iCacheMaintenance = Flipped(new CacheMaintenanceHandshakePort)
}

class MemReqStage
    extends BaseStage(
      new MemReqNdPort,
      new MemResNdPort,
      MemReqNdPort.default,
      Some(new MemReqPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  // Workaround
  val isUncachedAddressRange = if (isFullUncachedPatch) {
    VecInit(
      "h_1f".U(8.W),
      "h_bf".U(8.W)
      //      "h_1fd0".U(16.W), // Chiplab only
      //      "h_1fe0".U(16.W), // Serial port
      //      "h_1fe7".U(16.W), // FPGA: NAND flash
      //      "h_1fe8".U(16.W), // FPGA: NAND flash
      //      "h_1ff0".U(16.W) // FPGA: Xilinx DMFE
    ).contains(selectedIn.translatedMemReq.addr(Width.Mem._addr - 1, Width.Mem._addr - 8))
  } else {
    false.B
  }

  val isTrueCached = selectedIn.isCached && !isUncachedAddressRange
  val isMemReq     = WireDefault(selectedIn.translatedMemReq.isValid)

  // Fallback output
  out.wb                      := selectedIn.wb
  out.isUnsigned              := selectedIn.translatedMemReq.read.isUnsigned
  out.isCached                := isTrueCached
  out.dataMask                := selectedIn.translatedMemReq.mask
  out.isMemReq                := isMemReq
  out.isAtomicStore           := selectedIn.isAtomicStore
  out.isAtomicStoreSuccessful := selectedIn.isAtomicStoreSuccessful
  out.isRead                  := true.B

  // Fallback peer
  peer.dCacheReq.client                 := selectedIn.translatedMemReq
  peer.uncachedReq.client               := selectedIn.translatedMemReq
  peer.dCacheReq.client.isValid         := false.B
  peer.uncachedReq.client.isValid       := false.B
  peer.dCacheMaintenance.client.control := CacheMaintenanceControlNdPort.default
  peer.iCacheMaintenance.client.control := CacheMaintenanceControlNdPort.default
  peer.dCacheMaintenance.client.addr    := selectedIn.translatedMemReq.addr
  peer.iCacheMaintenance.client.addr    := selectedIn.translatedMemReq.addr

  // CACOP workaround
  val dCacheBitsDelta   = Param.Width.DCache._addr + Param.Width.DCache._byteOffset - Param.Width.DCache._indexOffsetMax
  val iCacheBitsDelta   = Param.Width.ICache._addr + Param.Width.ICache._byteOffset - Param.Width.ICache._indexOffsetMax
  val maxCacheBitsDelta = dCacheBitsDelta.max(iCacheBitsDelta)
  val maxCacheMaintenanceCount = Math.pow(2, maxCacheBitsDelta).toInt - 1
  val isDCacheWorkaround       = dCacheBitsDelta > 0
  val isICacheWorkaround       = iCacheBitsDelta > 0
  val cacheMaintenanceCountDownReg = Option.when(isDCacheWorkaround || isICacheWorkaround)(
    RegInit(maxCacheMaintenanceCount.U)
  )
  cacheMaintenanceCountDownReg.foreach(reg => reg := reg)

  // Handle pipelined input
  when(selectedIn.wb.instInfo.isValid) {
    when(selectedIn.translatedMemReq.isValid && selectedIn.wb.instInfo.exceptionPos === ExceptionPos.none) {
      // Whether last memory request is submitted
      when(io.out.ready) {
        when(isTrueCached) {
          peer.dCacheReq.client.isValid := true.B
          isComputed                    := peer.dCacheReq.isReady
        }.otherwise {
          peer.uncachedReq.client.isValid := true.B
          isComputed                      := peer.uncachedReq.isReady
        }
      }.otherwise {
        isComputed := false.B
      }
    }

    // Handle cache maintenance
    val isCacheMaintenance =
      selectedIn.cacheMaintenance.control.isL1Valid || selectedIn.cacheMaintenance.control.isL2Valid

    cacheMaintenanceCountDownReg.foreach { reg =>
      peer.dCacheMaintenance.client.addr := Cat(
        selectedIn.translatedMemReq.addr(Width.Mem._addr - 1, Width.Mem._addr - Param.Width.DCache._tag),
        reg,
        selectedIn.translatedMemReq.addr(Param.Width.DCache._indexOffsetMax - 1, 0)
      )
      peer.iCacheMaintenance.client.addr := Cat(
        selectedIn.translatedMemReq.addr(Width.Mem._addr - 1, Width.Mem._addr - Param.Width.ICache._tag),
        reg,
        selectedIn.translatedMemReq.addr(Param.Width.ICache._indexOffsetMax - 1, 0)
      )
    }

    when(selectedIn.wb.instInfo.exceptionPos === ExceptionPos.none) {
      switch(selectedIn.cacheMaintenance.target) {
        is(CacheMaintenanceTargetType.data) {
          when(isCacheMaintenance) {
            isComputed := false.B
            when(peer.iCacheMaintenance.isReady) {
              peer.dCacheMaintenance.client.control := selectedIn.cacheMaintenance.control
              if (isDCacheWorkaround) {
                isComputed := !cacheMaintenanceCountDownReg.get.orR && peer.dCacheMaintenance.isReady
                when(peer.dCacheMaintenance.isReady) {
                  cacheMaintenanceCountDownReg.get := cacheMaintenanceCountDownReg.get - 1.U
                }
              } else {
                isComputed := peer.dCacheMaintenance.isReady
              }
            }
          }
        }
        is(CacheMaintenanceTargetType.inst) {
          when(isCacheMaintenance) {
            isComputed := false.B
            when(peer.dCacheMaintenance.isReady) {
              peer.iCacheMaintenance.client.control := selectedIn.cacheMaintenance.control
              if (isICacheWorkaround) {
                isComputed := !cacheMaintenanceCountDownReg.get.orR && peer.iCacheMaintenance.isReady
                when(peer.iCacheMaintenance.isReady) {
                  cacheMaintenanceCountDownReg.get := cacheMaintenanceCountDownReg.get - 1.U
                }
              } else {
                isComputed := peer.iCacheMaintenance.isReady
              }
            }
          }
        }
      }

      // No cache maintenance for uncached address
      when(
        isCacheMaintenance && (
          selectedIn.cacheMaintenance.control.isCoherentByIndex || selectedIn.cacheMaintenance.control.isCoherentByHit
        ) && isUncachedAddressRange
      ) {
        peer.dCacheMaintenance.client.control.isL1Valid := false.B
        peer.iCacheMaintenance.client.control.isL2Valid := false.B
        isComputed                                      := true.B
      }
    }
  }

  // Submit pipelined result
  resultOutReg.valid := isComputed

  // Handle flush
  when(io.isFlush) {
    cacheMaintenanceCountDownReg.foreach(_ := maxCacheMaintenanceCount.U)
  }
}
