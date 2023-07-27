package frontend.fetch

import chisel3._
import chisel3.util._
import frontend.bundles.{FtqBlockBundle, PreDecoderResultNdPort}
import frontend.PreDecoder
import pipeline.common.{BaseStage, BaseStageWOSaveIn}
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.queue.InstQueueEnqNdPort
import spec.{Param, Width}

class InstPreDecodeNdPort extends Bundle {
  val enqInfos  = Vec(Param.fetchInstMaxNum, Valid(new FetchInstInfoBundle))
  val ftqLength = UInt(log2Ceil(Param.fetchInstMaxNum + 1).W)
  val ftqId     = UInt(Param.BPU.ftqPtrWitdh.W)
}

object InstPreDecodeNdPort {
  def default: InstPreDecodeNdPort = 0.U.asTypeOf(new InstPreDecodeNdPort)
}

class preDecodeRedirectBundle extends Bundle {
  val redirectFtqId = UInt(Param.BPU.ftqPtrWitdh.W)
  val redirectPc    = UInt(spec.Width.Mem.addr)
}
class InstPreDecodePeerPort extends Bundle {
  val predecodeRedirect = Bool()
  val redirectFtqId     = UInt(Param.BPU.ftqPtrWitdh.W)
  val redirectPc        = UInt(spec.Width.Mem.addr)
}
object InstPreDecodePeerPort {
  def default = 0.U.asTypeOf(new InstPreDecodePeerPort)
}

class InstPreDecodeStage
    extends BaseStageWOSaveIn(
      new InstPreDecodeNdPort,
      new InstQueueEnqNdPort,
      InstPreDecodeNdPort.default,
      Some(new InstPreDecodePeerPort)
    ) {
  val selectedIn = io.in.bits
  val peer       = io.peer.get
  val out        = if (Param.instQueueCombineSel) io.out.bits else resultOutReg.bits
  if (Param.instQueueCombineSel) {
    io.out.valid := io.in.valid
    io.in.ready  := io.out.ready
  }

  // default
  peer.predecodeRedirect := false.B
  peer.redirectPc        := 0.U
  peer.redirectFtqId     := 0.U
  if (!Param.isPredecode) {
    io.out.valid         := io.in.valid
    io.in.ready          := io.out.ready
    io.out.bits.enqInfos := io.in.bits.enqInfos
  } else {
    // default output
    out.enqInfos := io.in.bits.enqInfos

    // preDecode inst info
    val decodeResultVec = Wire(Vec(Param.fetchInstMaxNum, new PreDecoderResultNdPort))
    Seq.range(0, Param.fetchInstMaxNum).foreach { index =>
      val preDecoder = Module(new PreDecoder)
      preDecoder.io.pc       := selectedIn.enqInfos(index).bits.pcAddr
      preDecoder.io.inst     := selectedIn.enqInfos(index).bits.inst
      decodeResultVec(index) := preDecoder.io.result
    }

    val isImmJumpVec = Wire(Vec(Param.fetchInstMaxNum, Bool()))
    isImmJumpVec.zip(decodeResultVec).foreach {
      case (isImmJump, decodeResult) =>
        isImmJump := decodeResult.isUnconditionalJump && !decodeResult.isRegJump
    }

    // select the first immJump inst
    val immJumpIndex = PriorityEncoder(isImmJumpVec)

    val isPredecoderRedirect = WireDefault(false.B)
    isPredecoderRedirect := io.in.valid && io.in.ready && isImmJumpVec.asUInt.orR && !io.isFlush && (immJumpIndex +& 1.U < selectedIn.ftqLength)
    val isPredecoderRedirectReg = RegNext(isPredecoderRedirect, false.B)

    // peer output
    val ftqIdReg  = RegNext(selectedIn.ftqId, 0.U)
    val jumpPcReg = RegNext(decodeResultVec(immJumpIndex).jumpTargetAddr, 0.U)

    peer.predecodeRedirect := isPredecoderRedirectReg
    peer.redirectPc        := jumpPcReg
    peer.redirectFtqId     := ftqIdReg

    // output
    // cut block length
    val selectBlockLength = WireDefault(selectedIn.ftqLength)
    when(isPredecoderRedirect) {
      selectBlockLength := immJumpIndex +& 1.U
    }
    // when redirect,change output instOutput
    out.enqInfos.zipWithIndex.foreach {
      case (infoBundle, index) =>
        if (Param.fetchInstMaxNum == 1) {
          infoBundle.valid := true.B
        } else {
          infoBundle.valid := index.asUInt(log2Ceil(Param.fetchInstMaxNum + 1).W) < selectBlockLength
        }
        when((index + 1).U === selectBlockLength) {
          infoBundle.bits.ftqInfo.predictBranch := selectedIn
            .enqInfos(selectBlockLength - 1.U)
            .bits
            .ftqInfo
            .predictBranch || isPredecoderRedirect
          infoBundle.bits.ftqInfo.isLastInBlock := true.B
        }.otherwise {
          infoBundle.bits.ftqInfo.predictBranch := false.B
          infoBundle.bits.ftqInfo.isLastInBlock := false.B
        }
    }

    // Submit result
    when(io.in.ready && io.in.valid) {
      resultOutReg.valid := true.B
    }
    // Handle flush
    when(io.isFlush) {
      resultOutReg.valid := false.B
    }
  }

}
