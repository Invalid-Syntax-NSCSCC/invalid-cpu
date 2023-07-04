package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles._
import control.csrBundles.{EraBundle, LlbctlBundle}
import control.enums.ExceptionPos
import pipeline.commit.WbNdPort
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.BaseStage
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec.ExeInst.Sel
import spec.Param.isDiffTest
import frontend.bundles.ExeFtqPort
import spec._

// class ExCommitFtqNdPort(val queueSize: Int = Param.BPU.ftqSize) extends Bundle {
//   val ftqMetaUpdateValid       = Bool()
//   val ftqMetaUpdateFtbDirty    = Bool()
//   val ftqMetaUpdateJumpTarget  = UInt(spec.Width.Mem.addr)
//   val ftqMetaUpdateFallThrough = UInt(spec.Width.Mem.addr)
//   val ftqUpdateMetaId          = UInt(log2Ceil(queueSize).W)
// }

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
  val branchSetPort           = if (supportBranchCsr) Some(Output(new BackendRedirectPcNdPort)) else None
  val csrScoreboardChangePort = if (supportBranchCsr) Some(Output(new ScoreboardChangeNdPort)) else None
  val csrWriteStorePort       = if (supportBranchCsr) Some(Output(Valid(new CsrWriteNdPort))) else None

  // `Exe` <-> `StableCounter`
  val stableCounterReadPort = if (supportBranchCsr) Some(Flipped(new StableCounterReadPort)) else None

  val csrReadPort = if (supportBranchCsr) Some(Flipped(new CsrReadPort)) else None

  val csr = Input(new Bundle {
    val llbctl = new LlbctlBundle
    val era    = new EraBundle
  })

  val feedbackFtq = if (supportBranchCsr) Some(Flipped(new ExeFtqPort)) else None
}

// throw exception: 地址未对齐 ale
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
      resultOutReg.bits.gprWrite.data := selectedIn.instInfo.pc + 4.U
    }
  }

  /** CsrWrite
    */

  val isSyscall = selectedIn.exeOp === ExeInst.Op.syscall
  val isBreak   = selectedIn.exeOp === ExeInst.Op.break_

  resultOutReg.bits.instInfo.exceptionPos := selectedIn.instInfo.exceptionPos
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

    // if (dst_idx == Param.csrIssuePipelineIndex) {
    // def csrAddr = selectedIn.jumpBranchAddr
    // when(selectedIn.csrReadEn) {
    //   io.peer.get.csrReadPort.en   := true.B
    //   io.peer.get.csrReadPort.addr := csrAddr(13, 0)
    //   out.bits.csrData := Mux(
    //     csrAddr(31),
    //     0.U,
    //     io.peer.get.csrReadPort.data
    //   )
    // }
    // }

    def csrAddr = selectedIn.csrAddr

    io.peer.get.csrReadPort.get.en   := true.B
    io.peer.get.csrReadPort.get.addr := csrAddr

    val csrReadData = io.peer.get.csrReadPort.get.data

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

    val branchSetPort           = io.peer.get.branchSetPort.get
    val csrScoreboardChangePort = io.peer.get.csrScoreboardChangePort.get

    // branch set
    branchSetPort                := BackendRedirectPcNdPort.default
    csrScoreboardChangePort.en   := selectedIn.instInfo.needCsr
    csrScoreboardChangePort.addr := DontCare

    val feedbackFtq    = io.peer.get.feedbackFtq.get
    val jumpBranchInfo = WireDefault(alu.io.result.jumpBranchInfo)
    val inFtqInfo      = WireDefault(selectedIn.instInfo.ftqInfo)
    val fallThroughPc  = WireDefault(selectedIn.instInfo.pc + 4.U)

    feedbackFtq.queryPcBundle.ftqId := selectedIn.instInfo.ftqInfo.ftqId + 1.U
    val ftqQueryPc = feedbackFtq.queryPcBundle.pc

    // mis predict
    val branchDirectionMispredict = jumpBranchInfo.en ^ inFtqInfo.predictBranch
    val branchTargeMispredict = (
      jumpBranchInfo.en &&
        inFtqInfo.predictBranch &&
        jumpBranchInfo.pcAddr =/= ftqQueryPc
    ) || (
      !jumpBranchInfo.en &&
        !inFtqInfo.predictBranch &&
        inFtqInfo.isLastInBlock &&
        fallThroughPc =/= ftqQueryPc
    )

    branchSetPort.en    := (branchDirectionMispredict || branchTargeMispredict) && branchEnableFlag
    branchSetPort.ftqId := selectedIn.instInfo.ftqInfo.ftqId
    when(branchSetPort.en) {
      branchEnableFlag                                 := false.B
      resultOutReg.bits.instInfo.ftqInfo.isLastInBlock := true.B
    }
    branchSetPort.pcAddr := Mux(
      jumpBranchInfo.en,
      jumpBranchInfo.pcAddr,
      fallThroughPc
    )

    // is branch
    val isBranchInst = selectedIn.instInfo.ftqCommitInfo.isBranch
    feedbackFtq.commitBundle.ftqMetaUpdateValid := isBranchInst && branchEnableFlag
    feedbackFtq.commitBundle.ftqMetaUpdateFtbDirty := branchTargeMispredict ||
      (jumpBranchInfo.en && !inFtqInfo.isLastInBlock)
    feedbackFtq.commitBundle.ftqUpdateMetaId          := inFtqInfo.ftqId
    feedbackFtq.commitBundle.ftqMetaUpdateJumpTarget  := jumpBranchInfo.pcAddr
    feedbackFtq.commitBundle.ftqMetaUpdateFallThrough := fallThroughPc

    resultOutReg.bits.instInfo.ftqCommitInfo.isBranchSuccess := jumpBranchInfo.en

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
