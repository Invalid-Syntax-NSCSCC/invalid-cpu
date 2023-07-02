package pipeline.memory

import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import memory.bundles.{CacheMaintenanceControlNdPort, CacheMaintenanceHandshakePort, MemRequestHandshakePort}
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.BaseStage
import pipeline.memory.bundles.{CacheMaintenanceInstNdPort, MemRequestNdPort, StoreInfoBundle}
import control.enums.ExceptionPos
import pipeline.memory.enums.CacheMaintenanceTargetType
import spec._

class MemReqNdPort extends Bundle {
  val isAtomicStore           = new Bool()
  val isAtomicStoreSuccessful = new Bool()
  val translatedMemReq        = new MemRequestNdPort
  val isCached                = Bool()
  val gprAddr                 = UInt(Width.Reg.addr)
  val instInfo                = new InstInfoNdPort
  val cacheMaintenance        = new CacheMaintenanceInstNdPort
}

object MemReqNdPort {
  def default: MemReqNdPort = 0.U.asTypeOf(new MemReqNdPort)
}

class MemReqPeerPort extends Bundle {
  val dCacheReq         = Flipped(new MemRequestHandshakePort)
  val uncachedReq       = Flipped(new MemRequestHandshakePort)
  val dCacheMaintenance = Flipped(new CacheMaintenanceHandshakePort)
  val iCacheMaintenance = Flipped(new CacheMaintenanceHandshakePort)
  val commitStore       = Flipped(Decoupled())
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

  // Fallback output
  out.instInfo                := selectedIn.instInfo
  out.gprAddr                 := selectedIn.gprAddr
  out.isUnsigned              := selectedIn.translatedMemReq.read.isUnsigned
  out.isCached                := selectedIn.isCached
  out.dataMask                := selectedIn.translatedMemReq.mask
  out.isInstantReq            := selectedIn.translatedMemReq.isValid
  out.isAtomicStore           := selectedIn.isAtomicStore
  out.isAtomicStoreSuccessful := selectedIn.isAtomicStoreSuccessful
  out.isRead                  := true.B
  out.isPipelined             := true.B

  // Fallback peer
  peer.dCacheReq.client                 := selectedIn.translatedMemReq
  peer.uncachedReq.client               := selectedIn.translatedMemReq
  peer.dCacheReq.client.isValid         := false.B
  peer.uncachedReq.client.isValid       := false.B
  peer.commitStore.ready                := false.B
  peer.dCacheMaintenance.client.control := CacheMaintenanceControlNdPort.default
  peer.iCacheMaintenance.client.control := CacheMaintenanceControlNdPort.default
  peer.dCacheMaintenance.client.addr    := selectedIn.translatedMemReq.addr
  peer.iCacheMaintenance.client.addr    := selectedIn.translatedMemReq.addr

  // Store queue
  val storeIn = Wire(Decoupled(new StoreInfoBundle))
  val storeOut = Queue(
    storeIn,
    entries = Param.Count.Mem.storeQueueLen,
    pipe    = false,
    flow    = false,
    flush   = Some(io.isFlush)
  )
  storeIn.valid         := false.B
  storeIn.bits.addr     := selectedIn.translatedMemReq.addr
  storeIn.bits.data     := selectedIn.translatedMemReq.write.data
  storeIn.bits.mask     := selectedIn.translatedMemReq.mask
  storeIn.bits.isCached := selectedIn.isCached
  storeOut.ready        := false.B

  // Handle pipelined input
  when(selectedIn.instInfo.isValid && !peer.commitStore.valid) {
    switch(selectedIn.translatedMemReq.rw) {
      is(ReadWriteSel.read) {
        // Whether last memory request is submitted and no stores in queue and not committing store
        when(io.out.ready && !storeOut.valid) {
          when(selectedIn.isCached) {
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

      is(ReadWriteSel.write) {
        out.isInstantReq := false.B

        when(!selectedIn.isAtomicStore || selectedIn.isAtomicStoreSuccessful) {
          // Whether last memory request is submitted and not committing store
          when(io.out.ready) {
            storeIn.valid := true.B
            isComputed    := storeIn.ready
          }.otherwise {
            isComputed := false.B
          }
        }
      }
    }

    // Handle TLB maintenance and exception (instruction passing through)
    when(!selectedIn.translatedMemReq.isValid) {
      peer.dCacheReq.client.isValid   := false.B
      peer.uncachedReq.client.isValid := false.B
      out.isInstantReq                := false.B
      isComputed                      := true.B
    }

    // Handle cache maintenance
    val dCacheBitsDelta = Param.Width.DCache._addr + Param.Width.DCache._byteOffset - Param.Width.DCache._indexOffsetMax
    val iCacheBitsDelta = Param.Width.ICache._addr + Param.Width.ICache._byteOffset - Param.Width.ICache._indexOffsetMax
    val maxCacheBitsDelta        = dCacheBitsDelta.max(iCacheBitsDelta)
    val maxCacheMaintenanceCount = Math.pow(2, maxCacheBitsDelta).toInt - 1
    val isDCacheWorkaround       = dCacheBitsDelta > 0
    val isICacheWorkaround       = iCacheBitsDelta > 0
    val cacheMaintenanceCountDownReg = Option.when(isDCacheWorkaround || isICacheWorkaround)(
      RegInit(maxCacheMaintenanceCount.U)
    )
    cacheMaintenanceCountDownReg.foreach(reg => reg := reg)

    val isCacheMaintenance = selectedIn.cacheMaintenance.control.isInit ||
      selectedIn.cacheMaintenance.control.isCoherentByIndex ||
      selectedIn.cacheMaintenance.control.isCoherentByHit

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

    when(selectedIn.instInfo.exceptionPos === ExceptionPos.none) {
      switch(selectedIn.cacheMaintenance.target) {
        is(CacheMaintenanceTargetType.data) {
          peer.dCacheMaintenance.client.control := selectedIn.cacheMaintenance.control
          when(isCacheMaintenance) {
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
        is(CacheMaintenanceTargetType.inst) {
          peer.iCacheMaintenance.client.control := selectedIn.cacheMaintenance.control
          when(isCacheMaintenance) {
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
  }

  // Handle writeback store trigger
  peer.commitStore.ready := io.out.ready && Mux(
    storeOut.bits.isCached,
    peer.dCacheReq.isReady,
    peer.uncachedReq.isReady
  )

  when(peer.commitStore.valid) {
    out.isPipelined  := false.B
    out.isInstantReq := true.B
    out.isCached     := storeOut.bits.isCached
    out.isRead       := false.B
    out.dataMask     := storeOut.bits.mask

    isComputed := false.B

    // Whether can submit memory request instantly
    when(peer.commitStore.ready) {
      peer.dCacheReq.client.rw           := ReadWriteSel.write
      peer.dCacheReq.client.addr         := storeOut.bits.addr
      peer.dCacheReq.client.mask         := storeOut.bits.mask
      peer.dCacheReq.client.write.data   := storeOut.bits.data
      peer.uncachedReq.client.rw         := ReadWriteSel.write
      peer.uncachedReq.client.addr       := storeOut.bits.addr
      peer.uncachedReq.client.mask       := storeOut.bits.mask
      peer.uncachedReq.client.write.data := storeOut.bits.data

      storeOut.ready     := true.B
      resultOutReg.valid := true.B

      when(storeOut.bits.isCached) {
        peer.dCacheReq.client.isValid := true.B
      }.otherwise {
        peer.uncachedReq.client.isValid := true.B
      }
    }
  }.otherwise {
    // Submit pipelined result
    resultOutReg.valid := isComputed
  }
}
