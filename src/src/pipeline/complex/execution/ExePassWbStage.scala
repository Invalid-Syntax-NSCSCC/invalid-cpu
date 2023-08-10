package pipeline.complex.execution

import chisel3._
import chisel3.util._
import common.BaseStage
import common.bundles._
import control.bundles.{CsrReadPort, CsrWriteNdPort, StableCounterReadPort}
import control.csrBundles.{EraBundle, LlbctlBundle}
import control.enums.ExceptionPos
import pipeline.complex.execution.Alu
import frontend.bundles.ExeFtqPort
import pipeline.common.bundles.RobQueryPcPort
import pipeline.complex.bundles.InstInfoNdPort
import pipeline.complex.commit.WbNdPort
import spec.ExeInst.Sel
import spec.Param.isDiffTest
import spec._

class ExeNdPort extends Bundle {
  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)
  // Operands
  val leftOperand  = UInt(Width.Reg.data)
  val rightOperand = UInt(Width.Reg.data)

  // Branch jump addr
  val jumpBranchAddr    = UInt(Width.Reg.data)
  def loadStoreImm      = jumpBranchAddr
  def csrAddr           = jumpBranchAddr
  def tlbInvalidateInst = jumpBranchAddr
  def code              = jumpBranchAddr

  // GPR write (writeback)
  val gprWritePort = new RfAccessInfoNdPort

  val instInfo = new InstInfoNdPort
}

object ExeNdPort {
  def default = 0.U.asTypeOf(new ExeNdPort)
}

class ExePeerPort(supportBranchCsr: Boolean) extends Bundle {
  // `ExeStage` -> `Cu` (no delay)
  val branchSetPort     = if (supportBranchCsr) Some(Output(new BackendRedirectPcNdPort)) else None
  val csrWriteStorePort = if (supportBranchCsr) Some(Output(Valid(new CsrWriteNdPort))) else None

  // `Exe` <-> `StableCounter`
  val stableCounterReadPort = if (supportBranchCsr) Some(Flipped(new StableCounterReadPort)) else None

  val csrReadPort = if (supportBranchCsr) Some(Flipped(new CsrReadPort)) else None

  val csr = Input(new Bundle {
    val llbctl = new LlbctlBundle
    val era    = new EraBundle
  })

  val feedbackFtq = if (supportBranchCsr) Some(Flipped(new ExeFtqPort)) else None

  val robQueryPcPort = if (supportBranchCsr) Some(Flipped(new RobQueryPcPort)) else None
}

class ExePassWbStage(supportBranchCsr: Boolean = true)
    extends BaseStage(
      new ExeNdPort,
      new WbNdPort,
      ExeNdPort.default,
      Some(new ExePeerPort(supportBranchCsr))
    ) {

  // ALU module
  val alu = Module(new Alu)

  isComputed                 := alu.io.outputValid
  resultOutReg.valid         := isComputed && selectedIn.instInfo.isValid
  resultOutReg.bits.instInfo := selectedIn.instInfo

  // ALU input
  alu.io.isFlush                := io.isFlush
  alu.io.inputValid             := selectedIn.instInfo.isValid
  alu.io.aluInst.op             := selectedIn.exeOp
  alu.io.aluInst.leftOperand    := selectedIn.leftOperand
  alu.io.aluInst.rightOperand   := selectedIn.rightOperand
  alu.io.aluInst.jumpBranchAddr := selectedIn.jumpBranchAddr // also load-store imm

  // ALU output

  // write-back information fallback
  resultOutReg.bits.gprWrite.en   := false.B
  resultOutReg.bits.gprWrite.addr := zeroWord
  resultOutReg.bits.gprWrite.data := zeroWord

  // write-back information selection
  resultOutReg.bits.gprWrite.en   := selectedIn.gprWritePort.en
  resultOutReg.bits.gprWrite.addr := selectedIn.gprWritePort.addr

  if (supportBranchCsr) {
    io.peer.get.robQueryPcPort.get.robId := selectedIn.instInfo.robId
  }

  switch(selectedIn.exeSel) {
    is(Sel.logic) {
      resultOutReg.bits.gprWrite.data := alu.io.result.logic
    }
    is(Sel.shift) {
      resultOutReg.bits.gprWrite.data := alu.io.result.shift
    }
    is(Sel.arithmetic) {
      resultOutReg.bits.gprWrite.data := alu.io.result.arithmetic
    }
    is(Sel.jumpBranch) {
      if (supportBranchCsr) {
        resultOutReg.bits.gprWrite.data := io.peer.get.robQueryPcPort.get.pc + 4.U
      }
    }
  }

  /** CsrWrite
    */

  val isSyscall = selectedIn.exeOp === ExeInst.Op.syscall
  val isBreak   = selectedIn.exeOp === ExeInst.Op.break_

  when(selectedIn.instInfo.exceptionPos === ExceptionPos.none) {
    when(isSyscall) {
      resultOutReg.bits.instInfo.exceptionPos    := ExceptionPos.backend
      resultOutReg.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.sys
    }.elsewhen(isBreak) {
      resultOutReg.bits.instInfo.exceptionPos    := ExceptionPos.backend
      resultOutReg.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.brk
    }
  }

  if (supportBranchCsr) {
    if (isDiffTest) {
      resultOutReg.bits.instInfo.timerInfo.get.isCnt := VecInit(ExeInst.Op.rdcntvl_w, ExeInst.Op.rdcntvh_w)
        .contains(selectedIn.exeOp)
      resultOutReg.bits.instInfo.timerInfo.get.timer64 := io.peer.get.stableCounterReadPort.get.output
    }

    switch(selectedIn.exeOp) {
      is(ExeInst.Op.rdcntvl_w) {
        resultOutReg.bits.gprWrite.data := io.peer.get.stableCounterReadPort.get.output(wordLength - 1, 0)
      }

      is(ExeInst.Op.rdcntvh_w) {
        resultOutReg.bits.gprWrite.data := io.peer.get.stableCounterReadPort.get
          .output(doubleWordLength - 1, wordLength)
      }
    }

    def csrAddr = selectedIn.csrAddr

    io.peer.get.csrReadPort.get.en   := true.B
    io.peer.get.csrReadPort.get.addr := csrAddr

    val csrReadData = Mux(csrAddr(31), zeroWord, io.peer.get.csrReadPort.get.data)

    def csrWriteStorePort = io.peer.get.csrWriteStorePort.get
    csrWriteStorePort.valid     := false.B
    csrWriteStorePort.bits.en   := false.B
    csrWriteStorePort.bits.addr := csrAddr
    csrWriteStorePort.bits.data := DontCare

    switch(selectedIn.exeOp) {
      is(ExeInst.Op.csrrd) {
        resultOutReg.bits.gprWrite.data := csrReadData
      }
      is(ExeInst.Op.csrwr) {
        io.peer.get.csrWriteStorePort.get.valid   := true.B
        io.peer.get.csrWriteStorePort.get.bits.en := true.B
        csrWriteStorePort.bits.data               := selectedIn.leftOperand
        resultOutReg.bits.gprWrite.data           := csrReadData
      }
      is(ExeInst.Op.csrxchg) {
        io.peer.get.csrWriteStorePort.get.valid   := true.B
        io.peer.get.csrWriteStorePort.get.bits.en := true.B
        // lop: write value  rop: mask
        val gprWriteDataVec = Wire(Vec(wordLength, Bool()))
        selectedIn.leftOperand.asBools
          .lazyZip(selectedIn.rightOperand.asBools)
          .lazyZip(csrReadData.asBools)
          .lazyZip(gprWriteDataVec)
          .foreach {
            case (write, mask, origin, target) =>
              target := Mux(mask, write, origin)
          }
        csrWriteStorePort.bits.data     := gprWriteDataVec.asUInt
        resultOutReg.bits.gprWrite.data := csrReadData
      }
    }

    val branchEnableFlag = RegInit(true.B)

    val branchSetPort = io.peer.get.branchSetPort.get

    // branch set
    branchSetPort := BackendRedirectPcNdPort.default

    val feedbackFtq    = io.peer.get.feedbackFtq.get
    val jumpBranchInfo = WireDefault(alu.io.result.jumpBranchInfo)
    val inFtqInfo      = WireDefault(selectedIn.instInfo.ftqInfo)
    val fallThroughPc  = WireDefault(io.peer.get.robQueryPcPort.get.pc + 4.U)

    feedbackFtq.queryPcBundle.ftqId := selectedIn.instInfo.ftqInfo.ftqId + 1.U
    val ftqQueryPc = feedbackFtq.queryPcBundle.pc

    // mis predict
    val branchDirectionMispredict = jumpBranchInfo.en ^ inFtqInfo.predictBranch
    val branchTargetMispredict = (
      jumpBranchInfo.en &&
        inFtqInfo.predictBranch &&
        jumpBranchInfo.pcAddr =/= ftqQueryPc
    ) || (
      !jumpBranchInfo.en &&
        !inFtqInfo.predictBranch &&
        inFtqInfo.isLastInBlock &&
        fallThroughPc =/= ftqQueryPc
    )

    // is branch
    val isBranchInst = selectedIn.instInfo.ftqCommitInfo.isBranch

    val isRedirect = (branchDirectionMispredict || branchTargetMispredict) && branchEnableFlag && isBranchInst
    when(isRedirect) {
      branchEnableFlag                                 := false.B
      resultOutReg.bits.instInfo.ftqInfo.isLastInBlock := true.B
    }

    if (Param.usePmu) {
      resultOutReg.bits.instInfo.ftqCommitInfo.directionMispredict.get := branchDirectionMispredict && branchEnableFlag && isBranchInst
      resultOutReg.bits.instInfo.ftqCommitInfo.targetMispredict.get := branchTargetMispredict && branchEnableFlag && isBranchInst
    }

    branchSetPort.en                                := isRedirect
    branchSetPort.ftqId                             := selectedIn.instInfo.ftqInfo.ftqId
    feedbackFtq.feedBack.fixGhrBundle.isExeFixValid := branchDirectionMispredict && branchEnableFlag && isBranchInst
    feedbackFtq.feedBack.fixGhrBundle.exeFixFirstBrTaken := jumpBranchInfo.en && !inFtqInfo.isPredictValid && branchEnableFlag && isBranchInst // TODO predictValid
    feedbackFtq.feedBack.fixGhrBundle.exeFixIsTaken := jumpBranchInfo.en

    branchSetPort.pcAddr := Mux(
      jumpBranchInfo.en,
      jumpBranchInfo.pcAddr,
      fallThroughPc
    )

    if (Param.exeFeedBackFtqDelay) {

      feedbackFtq.feedBack.commitBundle.ftqMetaUpdateValid := (RegNext(isBranchInst, false.B) ||
        (RegNext(!isBranchInst, false.B) && RegNext(inFtqInfo.predictBranch, false.B))) && RegNext(
        branchEnableFlag,
        false.B
      )
      feedbackFtq.feedBack.commitBundle.ftqMetaUpdateFtbDirty := RegNext(branchTargetMispredict, false.B) ||
        (RegNext(jumpBranchInfo.en, false.B) && !RegNext(inFtqInfo.isLastInBlock, false.B)) ||
        (RegNext(!isBranchInst, false.B) && RegNext(inFtqInfo.predictBranch, false.B))
      feedbackFtq.feedBack.commitBundle.ftqUpdateMetaId          := RegNext(inFtqInfo.ftqId, 0.U)
      feedbackFtq.feedBack.commitBundle.ftqMetaUpdateJumpTarget  := RegNext(jumpBranchInfo.pcAddr, 0.U)
      feedbackFtq.feedBack.commitBundle.ftqMetaUpdateFallThrough := RegNext(fallThroughPc, 0.U)
    } else {

      feedbackFtq.feedBack.commitBundle.ftqMetaUpdateValid := (isBranchInst || (!isBranchInst && inFtqInfo.predictBranch)) && branchEnableFlag
      feedbackFtq.feedBack.commitBundle.ftqMetaUpdateFtbDirty := branchTargetMispredict ||
        (jumpBranchInfo.en && !inFtqInfo.isLastInBlock) || (!isBranchInst && inFtqInfo.predictBranch)
      feedbackFtq.feedBack.commitBundle.ftqUpdateMetaId          := inFtqInfo.ftqId
      feedbackFtq.feedBack.commitBundle.ftqMetaUpdateJumpTarget  := jumpBranchInfo.pcAddr
      feedbackFtq.feedBack.commitBundle.ftqMetaUpdateFallThrough := fallThroughPc
    }

    resultOutReg.bits.instInfo.ftqCommitInfo.isBranchSuccess := jumpBranchInfo.en
    resultOutReg.bits.instInfo.ftqCommitInfo.isRedirect := isRedirect || selectedIn.instInfo.ftqCommitInfo.isRedirect

    val isErtn = WireDefault(selectedIn.exeOp === ExeInst.Op.ertn)
    val isIdle = WireDefault(selectedIn.exeOp === ExeInst.Op.idle)

    when(isBranchInst || isIdle || isErtn) {
      resultOutReg.bits.instInfo.forbidParallelCommit := true.B
    }

    when(io.isFlush) {
      branchEnableFlag := true.B
    }
  }
}
