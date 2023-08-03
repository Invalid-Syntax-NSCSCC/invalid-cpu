package frontend.fetch

import chisel3._
import chisel3.util._
import common.BaseStage
import frontend.bundles.{FtqBlockBundle, InstMemResponseNdPort}
import spec.{Param, Width}

class InstResNdPort extends Bundle {
  val ftqBlock  = new FtqBlockBundle
  val ftqId     = UInt(Param.BPU.ftqPtrWidth.W)
  val exception = Valid(UInt(Width.Csr.exceptionIndex))
}

object InstResNdPort {
  def default: InstResNdPort = 0.U.asTypeOf(new InstResNdPort)
}

class InstResPeerPort extends Bundle {
  val memRes = Input(new InstMemResponseNdPort)
}

class InstResStage
    extends BaseStage(
      new InstResNdPort,
      new InstPreDecodeNdPort,
      InstResNdPort.default,
      Some(new InstResPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  val isLastHasReq = RegNext(false.B, false.B)

  // Fallback output
  out.ftqLength := selectedIn.ftqBlock.length
  out.ftqId     := selectedIn.ftqId

  out.enqInfos.zipWithIndex.foreach {
    case (infoBundle, index) =>
      infoBundle.bits.pcAddr := selectedIn.ftqBlock.startPc + index.asUInt(Width.Mem.addr) * 4.U
      // infoBundle.bits.ftqInfo.idxInBlock := index.U
      if (Param.fetchInstMaxNum == 1) {
        infoBundle.bits.inst := peer.memRes.read.dataVec(0)
        infoBundle.valid     := true.B
      } else {
        val fetchIndex = WireDefault(
          selectedIn.ftqBlock.startPc(Param.Width.ICache._byteOffset - 1, Param.Width.ICache._instOffset) + index.U
        )
        infoBundle.bits.inst := peer.memRes.read.dataVec(fetchIndex)
        infoBundle.valid     := index.asUInt(log2Ceil(Param.fetchInstMaxNum + 1).W) < selectedIn.ftqBlock.length
      }
      infoBundle.bits.exceptionValid := selectedIn.exception.valid
      infoBundle.bits.exception      := selectedIn.exception.bits
      infoBundle.bits.ftqInfo.ftqId  := selectedIn.ftqId
      when((index + 1).U === selectedIn.ftqBlock.length) {
        infoBundle.bits.ftqInfo.predictBranch := selectedIn.ftqBlock.predictTaken
        infoBundle.bits.ftqInfo.isLastInBlock := true.B
      }.otherwise {
        infoBundle.bits.ftqInfo.predictBranch := false.B
        infoBundle.bits.ftqInfo.isLastInBlock := false.B
      }
  }

  when(selectedIn.ftqBlock.isValid) {
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
