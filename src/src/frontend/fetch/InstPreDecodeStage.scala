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
  val ftqBlock  = new FtqBlockBundle
  val ftqId     = UInt(Param.BPU.ftqPtrWitdh.W)
  val exception = Valid(UInt(Width.Csr.exceptionIndex))
  val instVec   = Vec(Param.fetchInstMaxNum, UInt(Width.Mem.data))
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
  val out        = resultOutReg.bits

  // default
  peer.predecodeRedirect := false.B
  peer.redirectPc        := 0.U
  peer.redirectFtqId     := 0.U

  // select in data
  val pcVec = Wire(Vec(Param.fetchInstMaxNum, UInt(spec.Width.Mem.addr)))
  pcVec.zipWithIndex.foreach {
    case (pc, index) =>
      pc := selectedIn.ftqBlock.startPc + 4.U * index.U
  }
  val instVec = Wire(Vec(Param.fetchInstMaxNum, UInt(Width.Mem.data)))
  instVec.zipWithIndex.foreach {
    case (instVal, index) =>
      val fetchIndex = WireDefault(
        selectedIn.ftqBlock.startPc(Param.Width.ICache._fetchOffset - 1, Param.Width.ICache._instOffset) + index
          .asUInt(
            log2Ceil(Param.fetchInstMaxNum).W
          )
      )
      // TODO support crossCacheline
      instVal := selectedIn.instVec(fetchIndex)
  }

  // preDecode inst info
  val decodeResultVec = Wire(Vec(Param.fetchInstMaxNum, new PreDecoderResultNdPort))
  Seq.range(0, Param.fetchInstMaxNum).foreach { index =>
    val preDecoder = Module(new PreDecoder)
    preDecoder.io.pc       := pcVec(index)
    preDecoder.io.inst     := instVec(index)
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
  if (Param.isPredecode) {
    isPredecoderRedirect := io.in.valid && io.in.ready && isImmJumpVec.asUInt.orR && !io.isFlush && (immJumpIndex +& 1.U < selectedIn.ftqBlock.length)
  }

  val isPredecoderRedirectReg = RegNext(isPredecoderRedirect, false.B)

  // peer output
  val ftqIdReg  = RegNext(selectedIn.ftqId, 0.U)
  val jumpPcReg = RegNext(decodeResultVec(immJumpIndex).jumpTargetAddr, 0.U)

  if (Param.isPredecode) {
    peer.predecodeRedirect := isPredecoderRedirectReg
    peer.redirectPc        := jumpPcReg
    peer.redirectFtqId     := ftqIdReg
  }

  // output
  // cut block length
  val selectBlockLength = WireDefault(selectedIn.ftqBlock.length)
  when(isPredecoderRedirect) {
    selectBlockLength := immJumpIndex +& 1.U
  }
  // select instOutput
  out.enqInfos.zipWithIndex.foreach {
    case (infoBundle, index) =>
      infoBundle.bits.pcAddr             := pcVec(index)
      infoBundle.bits.ftqInfo.idxInBlock := index.U
      if (Param.fetchInstMaxNum == 1) {
        infoBundle.bits.inst := instVec(0)
        infoBundle.valid     := true.B
      } else {
        infoBundle.bits.inst := instVec(index)
        infoBundle.valid     := index.asUInt(log2Ceil(Param.fetchInstMaxNum + 1).W) < selectBlockLength
      }
      infoBundle.bits.exceptionValid := selectedIn.exception.valid
      infoBundle.bits.exception      := selectedIn.exception.bits
      infoBundle.bits.ftqInfo.ftqId  := selectedIn.ftqId
      when((index + 1).U === selectBlockLength) {
        infoBundle.bits.ftqInfo.predictBranch := selectedIn.ftqBlock.predictTaken || isPredecoderRedirect
        infoBundle.bits.ftqInfo.isLastInBlock := true.B
      }.otherwise {
        infoBundle.bits.ftqInfo.predictBranch := false.B
        infoBundle.bits.ftqInfo.isLastInBlock := false.B
      }
  }

  // Submit result
  when(io.in.ready && io.in.valid) {
    when(selectedIn.ftqBlock.isValid) {
      resultOutReg.valid := true.B
    }
  }
  // Handle flush
  when(io.isFlush) {
    resultOutReg.valid := false.B
  }
}
