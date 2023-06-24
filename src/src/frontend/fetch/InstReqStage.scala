package frontend.fetch

import chisel3.{Bundle, _}
import chisel3.util._
import frontend.bundles.{ICacheRequestHandshakePort, ICacheRequestNdPort}
import pipeline.common.BaseStage
import spec.{Csr, Width}

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

  val isAdef    = WireDefault(selectedIn.pc(1, 0).orR) //  pc is not aline
  val tlbExcp   = WireDefault(peer.exception.valid)
  val excpValid = WireDefault(isAdef || tlbExcp)

  // Fallback output
  out.pc              := selectedIn.pc
  out.isValid         := selectedIn.translatedMemReq.isValid || isAdef
  out.exception.valid := excpValid
  out.exception.bits  := Mux(isAdef, Csr.ExceptionIndex.adef, peer.exception.bits)

  // Fallback peer
  peer.memReq.client := selectedIn.translatedMemReq

  when(selectedIn.translatedMemReq.isValid && (!excpValid)) {
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
  }.elsewhen(excpValid) {
    // when pc is not aline or has a tlb excption, do not send mem request but still submit to backend to solve
    peer.memReq.client.isValid := false.B
    isComputed                 := true.B
    resultOutReg.valid         := isComputed
  }
}
