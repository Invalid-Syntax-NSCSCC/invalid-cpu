package frontend.instFetchStages

import pipeline.common.BaseStage
import chisel3.Bundle
import chisel3._
import chisel3.util._
import spec.Width
import frontend.bundles.{ICacheRequestHandshakePort,ICacheRequestNdPort}

class InstReqNdPort extends Bundle {
  val translatedMemReq = new ICacheRequestNdPort 
  val addr     = UInt(Width.Mem.addr)
}

object InstReqNdPort {
  def default: InstReqNdPort = 0.U.asTypeOf(new InstReqNdPort)
}

class InstReqPeerPort extends Bundle {
  val iCacheReq          = Flipped(new ICacheRequestHandshakePort)
  val isAfterMemReqFlush = Input(Bool())
}

class InstReqStage
    extends BaseStage(
      new InstReqNdPort,
      new InstResNdPort,
      InstReqNdPort.default,
      Some(new InstReqPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  // Fallback output
  out.addr     := selectedIn.addr
  out.isHasReq := selectedIn.translatedMemReq.isValid
  out.isCached := selectedIn.translatedMemReq.isCached

  // Fallback peer
  peer.iCacheReq.client      := selectedIn.translatedMemReq


  when(selectedIn.translatedMemReq.isValid) {
    // Whether memory request is submitted
    isComputed := peer.iCacheReq.isReady

    // Pending when this memory request might be flushed in the future
    when(peer.isAfterMemReqFlush) {
      peer.iCacheReq.client.isValid := false.B
      isComputed                    := false.B
    }

    when(selectedIn.translatedMemReq.isValid && peer.iCacheReq.isReady) {
      peer.iCacheReq.client.isValid  := true.B
      peer.iCacheReq.client.addr     := selectedIn.translatedMemReq.addr
      peer.iCacheReq.client.isCached := selectedIn.translatedMemReq.isCached
    }
    // Submit result
    resultOutReg.valid := isComputed
  }
}
