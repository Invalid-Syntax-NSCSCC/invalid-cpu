package pipeline.memory

import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import control.enums.ExceptionPos
import memory.bundles.{CacheMaintenanceControlNdPort, CacheMaintenanceHandshakePort, MemRequestHandshakePort}
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.{BaseStage, LookupQueue}
import pipeline.memory.bundles.{CacheMaintenanceInstNdPort, MemRequestNdPort, StoreInfoBundle}
import pipeline.memory.enums.CacheMaintenanceTargetType
import spec._
import spec.Param.isUncachedPatch

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

  // Workaround
  val isUncachedAddressRange = if (isUncachedPatch) {
    VecInit(
      "h_1faf".U(16.W),
      "h_bfaf".U(16.W),
      "h_1fd0".U(16.W), // Chiplab only
      "h_1fe0".U(16.W) // Chiplab only
    ).contains(selectedIn.translatedMemReq.addr(Width.Mem._addr - 1, Width.Mem._addr - 16))
  } else {
    false.B
  }

  val isTrueCached = selectedIn.isCached && !isUncachedAddressRange
  val isInstantReq = WireDefault(selectedIn.translatedMemReq.isValid)

  // Fallback output
  out.instInfo                := selectedIn.instInfo
  out.gprAddr                 := selectedIn.gprAddr
  out.isUnsigned              := selectedIn.translatedMemReq.read.isUnsigned
  out.isCached                := isTrueCached
  out.dataMask                := selectedIn.translatedMemReq.mask
  out.isInstantReq            := isInstantReq
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
  val storeQueue = Module(
    new LookupQueue(
      new StoreInfoBundle,
      entries         = Param.Count.Mem.storeQueueLen,
      lookupInFactory = UInt(Width.Mem.addr),
      lookupFn        = (in: UInt, entry: StoreInfoBundle) => in(5, 2) === entry.addr(5, 2),
      pipe            = false,
      flow            = false,
      hasFlush        = true
    )
  )
  storeQueue.io.lookup.in := selectedIn.translatedMemReq.addr
  storeQueue.io.queue.flush.foreach(_ := io.isFlush)
  val storeIn = Wire(Decoupled(new StoreInfoBundle))
  storeQueue.io.queue.enq <> storeIn
  val storeOut = Wire(Flipped(Decoupled(new StoreInfoBundle)))
  storeOut              <> storeQueue.io.queue.deq
  storeIn.valid         := false.B
  storeIn.bits.addr     := selectedIn.translatedMemReq.addr
  storeIn.bits.data     := selectedIn.translatedMemReq.write.data
  storeIn.bits.mask     := selectedIn.translatedMemReq.mask
  storeIn.bits.isCached := isTrueCached
  storeOut.ready        := false.B

  // Handle pipelined input
  when(selectedIn.instInfo.isValid) {
    when(selectedIn.translatedMemReq.isValid) {
      switch(selectedIn.translatedMemReq.rw) {
        is(ReadWriteSel.read) {
          // Whether last memory request is submitted and no stores in queue and not committing store
          when(io.out.ready && !storeQueue.io.lookup.out) {
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

        is(ReadWriteSel.write) {
          isInstantReq := false.B

          when(!selectedIn.isAtomicStore || selectedIn.isAtomicStoreSuccessful) {
            storeIn.valid := true.B
            isComputed    := storeIn.ready
          }
        }
      }
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
      when(storeOut.valid) {
        // Wait until all store requests are processed
        when(isCacheMaintenance) {
          isComputed := false.B
        }
      }.otherwise {
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
  }

  // Handle writeback store trigger
  peer.commitStore.ready := Mux(
    storeOut.bits.isCached,
    peer.dCacheReq.isReady,
    peer.uncachedReq.isReady
  )

  when(peer.commitStore.valid) {
    peer.dCacheReq.client.isValid      := false.B
    peer.uncachedReq.client.isValid    := false.B
    peer.dCacheReq.client.rw           := ReadWriteSel.write
    peer.dCacheReq.client.addr         := storeOut.bits.addr
    peer.dCacheReq.client.mask         := storeOut.bits.mask
    peer.dCacheReq.client.write.data   := storeOut.bits.data
    peer.uncachedReq.client.rw         := ReadWriteSel.write
    peer.uncachedReq.client.addr       := storeOut.bits.addr
    peer.uncachedReq.client.mask       := storeOut.bits.mask
    peer.uncachedReq.client.write.data := storeOut.bits.data

    when(isInstantReq) {
      isComputed := false.B
    }

    // Whether can submit memory request instantly
    when(peer.commitStore.ready) {
      storeOut.ready := true.B

      when(storeOut.bits.isCached) {
        peer.dCacheReq.client.isValid := true.B
      }.otherwise {
        peer.uncachedReq.client.isValid := true.B
      }
    }
  }

  // Submit pipelined result
  resultOutReg.valid := isComputed
}
