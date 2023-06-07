package frontend.fetch

import pipeline.common.BaseStage
import chisel3.Bundle
import chisel3._
import chisel3.util._
import spec.Width
import frontend.bundles.{ICacheRequestHandshakePort, ICacheRequestNdPort}

class InstReqNdPort extends Bundle {
  val translatedMemReq = new ICacheRequestNdPort
  val pc               = UInt(Width.Mem.addr)
}

object InstReqNdPort {
  def default: InstReqNdPort = 0.U.asTypeOf(new InstReqNdPort)
}

class InstReqPeerPort extends Bundle {
  val memReq = Flipped(new ICacheRequestHandshakePort)
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
  out.pc      := selectedIn.pc
  out.isValid := selectedIn.translatedMemReq.isValid

  // Fallback peer
  peer.memReq.client := selectedIn.translatedMemReq

  when(selectedIn.translatedMemReq.isValid) {
    when(io.out.ready) {
      // Whether memory request is submitted
      isComputed := peer.memReq.isReady

      peer.memReq.client.isValid  := true.B
      peer.memReq.client.addr     := selectedIn.translatedMemReq.addr
      peer.memReq.client.isCached := selectedIn.translatedMemReq.isCached
    }.otherwise {
      isComputed := false.B
    }

    // Submit result
    resultOutReg.valid := isComputed
  }
}
