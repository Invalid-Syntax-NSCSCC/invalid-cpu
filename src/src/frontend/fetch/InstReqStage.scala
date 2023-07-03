package frontend.fetch

import chisel3._
import chisel3.util._
import frontend.bundles.{FtqBlockBundle, ICacheRequestHandshakePort, ICacheRequestNdPort}
import pipeline.common.BaseStage
import spec.{Csr, Param, Width}

class InstReqNdPort extends Bundle {
  val translatedMemReq = new ICacheRequestNdPort
  val ftqBlock         = new FtqBlockBundle
  val ftqId            = Input(UInt(Param.BPU.ftqPtrWitdh.W))
  val exception        = Valid(UInt(Width.Csr.exceptionIndex))
}

object InstReqNdPort {
  def default: InstReqNdPort = 0.U.asTypeOf(new InstReqNdPort)
}

class InstReqPeerPort extends Bundle {
  val memReq      = Flipped(new ICacheRequestHandshakePort)
  val ftqRedirect = Input(Bool())
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

  val excpValid = WireDefault(selectedIn.exception.valid)

  // Fallback output
  out.ftqBlock  := selectedIn.ftqBlock
  out.exception := selectedIn.exception

  // Fallback peer
  peer.memReq.client := selectedIn.translatedMemReq

  when(selectedIn.translatedMemReq.isValid && (!excpValid)) {
    when(io.out.ready && !peer.ftqRedirect) {
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
