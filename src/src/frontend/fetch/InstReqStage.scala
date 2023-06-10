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
  val memReq    = Flipped(new ICacheRequestHandshakePort)
  val exception = Flipped(Valid(UInt(Width.Csr.exceptionIndex)))
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
  out.pc := selectedIn.pc
  val isAdef = WireDefault(!out.pc(1, 0).orR) //  pc is not aline
  out.isValid         := selectedIn.translatedMemReq.isValid | isAdef
  out.exception.valid := isAdef | peer.exception.valid
  out.exception.bits  := Mux(isAdef, Csr.ExceptionIndex.adef, peer.exception.bits)

  // Fallback peer
  peer.memReq.client := selectedIn.translatedMemReq

  when(selectedIn.translatedMemReq.isValid !&& out.exception.valid) {
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
  }.elsewhen(out.exception.valid) {
    // when pc is not aline,still submit to backend to solve
    isComputed         := true.B
    resultOutReg.valid := isComputed
  }
}
