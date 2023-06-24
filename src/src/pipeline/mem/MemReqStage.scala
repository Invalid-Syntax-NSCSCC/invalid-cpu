package pipeline.mem

import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import memory.bundles.MemRequestHandshakePort
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.BaseStage
import pipeline.mem.bundles.{MemRequestNdPort, StoreInfoBundle}
import spec._

class MemReqNdPort extends Bundle {
  val translatedMemReq = new MemRequestNdPort
  val isCached         = Bool()
  val gprAddr          = UInt(Width.Reg.addr)
  val instInfo         = new InstInfoNdPort
}

object MemReqNdPort {
  def default: MemReqNdPort = 0.U.asTypeOf(new MemReqNdPort)
}

class MemReqPeerPort extends Bundle {
  val dCacheReq   = Flipped(new MemRequestHandshakePort)
  val uncachedReq = Flipped(new MemRequestHandshakePort)
  val commitStore = Flipped(Decoupled())
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
  out.instInfo     := selectedIn.instInfo
  out.gprAddr      := selectedIn.gprAddr
  out.isUnsigned   := selectedIn.translatedMemReq.read.isUnsigned
  out.isCached     := selectedIn.isCached
  out.dataMask     := selectedIn.translatedMemReq.mask
  out.isInstantReq := true.B
  out.isRead       := true.B
  out.isPipelined  := true.B

  // Fallback peer
  peer.dCacheReq.client           := selectedIn.translatedMemReq
  peer.uncachedReq.client         := selectedIn.translatedMemReq
  peer.dCacheReq.client.isValid   := false.B
  peer.uncachedReq.client.isValid := false.B
  peer.commitStore.ready          := false.B

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
