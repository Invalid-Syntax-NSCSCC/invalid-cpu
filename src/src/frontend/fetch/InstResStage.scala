package frontend.fetch

import chisel3._
import chisel3.util._
import frontend.bundles.InstMemResponseNdPort
import pipeline.common.BaseStage
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.queue.InstQueueEnqNdPort
import spec.{Param, Width}
import spec.Width

class InstResNdPort extends Bundle {
  val isValid   = Bool()
  val pc        = UInt(Width.Mem.addr)
  val exception = Valid(UInt(Width.Csr.exceptionIndex))
}

object InstResNdPort {
  def default: InstResNdPort = 0.U.asTypeOf(new InstResNdPort)
}

class InstResPeerPort extends Bundle {
  val memRes = Input(new InstMemResponseNdPort)
}

class InstEnqueuePort extends Bundle {
  val instInfo = new FetchInstInfoBundle
}

class InstResStage
    extends BaseStage(
      new InstResNdPort,
      new FetchInstInfoBundle,
      InstResNdPort.default,
      Some(new InstResPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  val isLastHasReq = RegNext(false.B, false.B)

  // Fallback output
  out.enqInfos.zipWithIndex.foreach {
    case (infoBundle, index) =>
      infoBundle.bits.pcAddr := selectedIn.pc + index.asUInt(Width.Mem.addr) * 4.U
      if (Param.fetchInstMaxNum == 1) {
        infoBundle.bits.inst := peer.memRes.read.dataVec(0)
        infoBundle.valid     := true.B
      } else {
        val fetchIndex = WireDefault(
          selectedIn.pc(Param.Width.ICache._fetchOffset - 1, Param.Width.ICache._instOffset) + index.asUInt(
            log2Ceil(Param.fetchInstMaxNum).W
          )
        )
        infoBundle.bits.inst := peer.memRes.read.dataVec(fetchIndex)
        infoBundle.valid     := fetchIndex >= index.asUInt(log2Ceil(Param.fetchInstMaxNum).W)
      }
      infoBundle.bits.exceptionValid := selectedIn.exception.valid
      infoBundle.bits.exception      := selectedIn.exception.bits
  }

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
