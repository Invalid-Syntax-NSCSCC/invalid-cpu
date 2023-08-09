package frontend.fetch

import chisel3._
import chisel3.util._
import common.NoSavedInBaseStage
import frontend.PreDecoder
import frontend.bpu.RAS
import frontend.bundles.PreDecoderResultNdPort
import pipeline.common.bundles.{FetchInstInfoBundle, InstQueueEnqNdPort}
import spec.Param

class InstPreDecodeNdPort extends Bundle {
  val enqInfos  = Vec(Param.fetchInstMaxNum, Valid(new FetchInstInfoBundle))
  val ftqLength = UInt(log2Ceil(Param.fetchInstMaxNum + 1).W)
  val ftqId     = UInt(Param.BPU.ftqPtrWidth.W)
}

object InstPreDecodeNdPort {
  def default: InstPreDecodeNdPort = 0.U.asTypeOf(new InstPreDecodeNdPort)
}

class preDecodeRedirectBundle extends Bundle {
  val redirectFtqId = UInt(Param.BPU.ftqPtrWidth.W)
  val redirectPc    = UInt(spec.Width.Mem.addr)
}
class ftqPreDecodeFixRasNdPort extends Bundle {
  val isPush       = Bool()
  val isPop        = Bool()
  val callAddr     = UInt(spec.Width.Mem.addr)
  val predictError = Bool()
}
class InstPreDecodePeerPort extends Bundle {
  val predecodeRedirect = Output(Bool())
  val predecoderBranch  = Output(Bool())
  val redirectFtqId     = Output(UInt(Param.BPU.ftqPtrWidth.W))
  val redirectPc        = Output(UInt(spec.Width.Mem.addr))
  val commitRasPort     = Input(Valid(new ftqPreDecodeFixRasNdPort))
}
object InstPreDecodePeerPort {
  def default = 0.U.asTypeOf(new InstPreDecodePeerPort)
}
//InstPreDecodeStage
// 1.when bpu do not predict unconditonal direct jump(include call),predecode Stage would trigger a redirect, else not
// 2.Stage would select the first branch (call ,ret, uncond) to do something
// 3.whether the call inst has predicted the correct addr
//           1:bpu predict call(may be direct or indirect); 2:bpu not predict,predecode predict direct call jump;
//          ; the return addr must be push
class InstPreDecodeStage
    extends NoSavedInBaseStage(
      new InstPreDecodeNdPort,
      new InstQueueEnqNdPort,
      InstPreDecodeNdPort.default,
      Some(new InstPreDecodePeerPort)
    ) {
  val selectedIn = io.in.bits
  val peer       = io.peer.get
  val out        = if (Param.instQueueCombineSel) io.out.bits else resultOutReg.bits
  if (Param.instQueueCombineSel) {
    // support write through when instQueue is empty
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

    val isDataValid = io.in.valid && io.in.ready && !io.isFlush

    // preDecode inst info
    val decodeResultVec = Wire(Vec(Param.fetchInstMaxNum, new PreDecoderResultNdPort))
    Seq.range(0, Param.fetchInstMaxNum).foreach { index =>
      val preDecoder = Module(new PreDecoder)
      preDecoder.io.pc       := selectedIn.enqInfos(index).bits.pcAddr
      preDecoder.io.inst     := selectedIn.enqInfos(index).bits.inst
      decodeResultVec(index) := preDecoder.io.result
    }

    val isErrorPredict = selectedIn.enqInfos(io.in.bits.ftqLength - 1.U).bits.ftqInfo.predictBranch &&
      !decodeResultVec(io.in.bits.ftqLength - 1.U).isBranch

    val isJumpVec = Wire(Vec(Param.fetchInstMaxNum, Bool()))
    isJumpVec.zip(decodeResultVec).foreach {
      case (isImmJump, decodeResult) =>
        isImmJump := decodeResult.isJump
    }

    // select the first  (call ,ret ,direct unconditional)branch inst
    val jumpIndex = PriorityEncoder(isJumpVec)
    val isJump    = isJumpVec.asUInt.orR && (jumpIndex +& 1.U <= selectedIn.ftqLength)

    // only immJump or ret can jump; indirect call would not jump
    // when met immediately jump but bpu do not predict jump, then triger a redirect
    // when met ret jump; redirect
    val canJump = ((decodeResultVec(jumpIndex).isImmJump || decodeResultVec(jumpIndex).isRet)
      && !selectedIn
        .enqInfos(jumpIndex)
        .bits
        .ftqInfo
        .predictBranch)
    val isPredecoderRedirect = WireDefault(false.B)
    isPredecoderRedirect := isDataValid && ((isJump && canJump) || isErrorPredict)
    val isPredecoderRedirectReg = RegNext(isPredecoderRedirect, false.B)
    peer.predecoderBranch := RegNext(isDataValid && (isJump && canJump), false.B)

    // connect return address stack module
    val rasModule = Module(new RAS)
    // connect predict result
    // when bpu predecict call;predecode would not trigger redirect,but still need to push
    rasModule.io.predictPush := isDataValid && decodeResultVec(jumpIndex).isCall && selectedIn.enqInfos(jumpIndex).valid
    rasModule.io.predictCallAddr := selectedIn.enqInfos(jumpIndex).bits.pcAddr + 4.U
    rasModule.io.predictPop := isDataValid && isJump && decodeResultVec(
      jumpIndex
    ).isRet
    // connect actual result; when branchPredict error, use commit info to fix RAS
    rasModule.io.push         := peer.commitRasPort.valid && peer.commitRasPort.bits.isPush
    rasModule.io.pop          := peer.commitRasPort.valid && peer.commitRasPort.bits.isPop
    rasModule.io.callAddr     := peer.commitRasPort.bits.callAddr
    rasModule.io.predictError := peer.commitRasPort.valid && peer.commitRasPort.bits.predictError

    // peer output
    // delay 1 circle
    val ftqIdReg = RegNext(selectedIn.ftqId, 0.U)
    val jumpPcReg =
      RegNext(
        Mux(
          decodeResultVec(jumpIndex).isImmJump,
          decodeResultVec(jumpIndex).jumpTargetAddr,
          Mux(
            decodeResultVec(jumpIndex).isRet,
            rasModule.io.topAddr,
            selectedIn.enqInfos(io.in.bits.ftqLength - 1.U).bits.pcAddr + 4.U
          )
        ),
        0.U
      )

    peer.predecodeRedirect := isPredecoderRedirectReg
    peer.redirectPc        := jumpPcReg
    peer.redirectFtqId     := ftqIdReg

    // output
    // cut block length
    val selectBlockLength = Mux(
      isPredecoderRedirect,
      jumpIndex +& 1.U,
      selectedIn.ftqLength
    )
//    WireDefault(selectedIn.ftqLength)
//    when(isPredecoderRedirect) {
//      selectBlockLength := jumpIndex +& 1.U
//    }
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
