package pipeline.execution

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import common.bundles.{PcSetNdPort, RfAccessInfoNdPort}
import control.csrBundles.{EraBundle, LlbctlBundle}
import control.enums.ExceptionPos
import pipeline.commit.WbNdPort
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.BaseStage
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec.ExeInst.Sel
import spec._
import spec.Param.isDiffTest
import control.bundles.StableCounterReadPort

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
  def csrData           = jumpBranchAddr
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
  val branchSetPort           = if (supportBranchCsr) Some(Output(new PcSetNdPort)) else None
  val csrScoreboardChangePort = if (supportBranchCsr) Some(Output(new ScoreboardChangeNdPort)) else None

  // `Exe` <-> `StableCounter`
  val stableCounterReadPort = if (supportBranchCsr) Some(Flipped(new StableCounterReadPort)) else None

  val csr = Input(new Bundle {
    val llbctl = new LlbctlBundle
    val era    = new EraBundle
  })
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

  switch(selectedIn.exeOp) {
    is(ExeInst.Op.csrrd) {
      resultOutReg.bits.gprWrite.data := selectedIn.csrData
    }
  }

  /** CsrWrite
    */

  def csrWriteData = resultOutReg.bits.instInfo.csrWritePort.data

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

  switch(selectedIn.exeOp) {
    is(ExeInst.Op.csrwr) {
      csrWriteData                    := selectedIn.leftOperand
      resultOutReg.bits.gprWrite.data := selectedIn.csrData
    }
    is(ExeInst.Op.csrxchg) {
      // lop: write value  rop: mask
      val gprWriteDataVec = Wire(Vec(wordLength, Bool()))
      selectedIn.leftOperand.asBools
        .lazyZip(selectedIn.rightOperand.asBools)
        .lazyZip(selectedIn.csrData.asBools)
        .lazyZip(gprWriteDataVec)
        .foreach {
          case (write, mask, origin, target) =>
            target := Mux(mask, write, origin)
        }
      csrWriteData                    := gprWriteDataVec.asUInt
      resultOutReg.bits.gprWrite.data := selectedIn.csrData
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

    val branchEnableFlag = RegInit(true.B)

    val branchSetPort           = io.peer.get.branchSetPort.get
    val csrScoreboardChangePort = io.peer.get.csrScoreboardChangePort.get

    // branch set
    branchSetPort    := PcSetNdPort.default
    branchSetPort.en := alu.io.result.jumpBranchInfo.en && branchEnableFlag
    when(alu.io.result.jumpBranchInfo.en) {
      branchEnableFlag := false.B
    }
    branchSetPort.pcAddr         := alu.io.result.jumpBranchInfo.pcAddr
    csrScoreboardChangePort.en   := selectedIn.instInfo.needCsr
    csrScoreboardChangePort.addr := selectedIn.instInfo.csrWritePort.addr

    val isErtn = WireDefault(selectedIn.exeOp === ExeInst.Op.ertn)
    val isIdle = WireDefault(selectedIn.exeOp === ExeInst.Op.idle)

    when(branchSetPort.en || isIdle || isErtn) {
      resultOutReg.bits.instInfo.forbidParallelCommit := true.B
    }

    resultOutReg.bits.instInfo.branchSuccess := branchSetPort.en

    when(io.isFlush) {
      branchEnableFlag := true.B
    }

  }
}
