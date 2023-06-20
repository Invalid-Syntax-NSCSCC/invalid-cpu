package frontend.fetch

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import control.bundles.PipelineControlNdPort
import memory.bundles.TlbTransPort
import memory.enums.TlbMemType
import pipeline.mem.bundles.MemCsrNdPort
import pipeline.mem.enums.AddrTransType
import pipeline.commit.bundles.InstInfoNdPort
import spec.Value.Csr
import spec.Width
import memory.bundles.MemResponseNdPort
import pipeline.common.BaseStage
import pipeline.dispatch.bundles.InstInfoBundle

class InstResNdPort extends Bundle {
  val isValid   = Bool()
  val pc        = UInt(Width.Mem.addr)
  val exception = Valid(UInt(Width.Csr.exceptionIndex))
}

object InstResNdPort {
  def default: InstResNdPort = 0.U.asTypeOf(new InstResNdPort)
}

class InstResPeerPort extends Bundle {
  val memRes = Input(new MemResponseNdPort)
}

class InstEnqueuePort extends Bundle {
  val instInfo = new InstInfoBundle
}

class InstResStage
    extends BaseStage(
      new InstResNdPort,
      new InstInfoBundle,
      InstResNdPort.default,
      Some(new InstResPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  val isLastHasReq = RegNext(false.B, false.B)

  // Fallback output
  out.pcAddr         := selectedIn.pc
  out.inst           := peer.memRes.read.data
  out.exceptionValid := selectedIn.exception.valid
  out.exception      := selectedIn.exception.bits

  when(selectedIn.isValid) {
    isComputed         := peer.memRes.isComplete | selectedIn.exception.valid
    resultOutReg.valid := isComputed
    isLastHasReq       := true.B
  }

  val shouldDiscardReg = RegInit(false.B)
  shouldDiscardReg := shouldDiscardReg

  when(io.isFlush && isLastHasReq && !peer.memRes.isComplete) {
    shouldDiscardReg := true.B
  }

  when(shouldDiscardReg) {
    when(peer.memRes.isComplete) {
      shouldDiscardReg := false.B
      isComputed       := true.B
    }.otherwise {
      isComputed := false.B
    }
  }
}
