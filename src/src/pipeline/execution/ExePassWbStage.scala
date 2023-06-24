package pipeline.execution

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import common.bundles.{PcSetPort, RfAccessInfoNdPort}
import control.csrRegsBundles.{EraBundle, LlbctlBundle}
import control.enums.ExceptionPos
import pipeline.commit.WbNdPort
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.BaseStage
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec.ExeInst.Sel
import spec._

class ExeNdPort extends Bundle {
  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)
  // Operands
  val leftOperand  = UInt(Width.Reg.data)
  val rightOperand = UInt(Width.Reg.data)

  // Branch jump addr
  val jumpBranchAddr = UInt(Width.Reg.data)
  def loadStoreImm   = jumpBranchAddr
  def csrData        = jumpBranchAddr

  // GPR write (writeback)
  val gprWritePort = new RfAccessInfoNdPort

  val instInfo = new InstInfoNdPort
}

object ExeNdPort {
  def default = (new ExeNdPort).Lit(
    _.exeSel -> ExeInst.Sel.none,
    _.exeOp -> ExeInst.Op.nop,
    _.leftOperand -> 0.U,
    _.rightOperand -> 0.U,
    _.gprWritePort -> RfAccessInfoNdPort.default,
    _.jumpBranchAddr -> zeroWord,
    _.instInfo -> InstInfoNdPort.default
  )
}

class ExePeerPort(supportBranchCsr: Boolean) extends Bundle {
  // `ExeStage` -> `Cu` (no delay)
  val branchSetPort           = if (supportBranchCsr) Some(Output(new PcSetPort)) else None
  val csrScoreboardChangePort = if (supportBranchCsr) Some(Output(new ScoreboardChangeNdPort)) else None
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
    is(ExeInst.Op.invtlb) {
      // lop : asid  rop : virtual addr
      resultOutReg.bits.instInfo.tlbInfo.registerAsid := selectedIn.leftOperand(9, 0)
      resultOutReg.bits.instInfo.tlbInfo.virtAddr     := selectedIn.rightOperand
    }
  }

  // when(selectedIn.instInfo.pc(1, 0).orR) {
  //   resultOutReg.bits.instInfo.isExceptionValid                          := true.B
  //   resultOutReg.bits.instInfo.exceptionRecords(Csr.ExceptionIndex.adef) := true.B
  // }

  // resultOutReg.bits.instInfo.load.en := Mux(
  //   isAle,
  //   0.U,
  //   Cat(
  //     0.U(2.W),
  //     selectedIn.exeOp === ExeInst.Op.ll,
  //     selectedIn.exeOp === ExeInst.Op.ld_w,
  //     selectedIn.exeOp === ExeInst.Op.ld_hu,
  //     selectedIn.exeOp === ExeInst.Op.ld_h,
  //     selectedIn.exeOp === ExeInst.Op.ld_bu,
  //     selectedIn.exeOp === ExeInst.Op.ld_b
  //   )
  // )
  // resultOutReg.bits.instInfo.store.en := Mux(
  //   isAle,
  //   0.U,
  //   Cat(
  //     0.U(4.W),
  //     io.peer.get.csr.llbctl.wcllb &&
  //       selectedIn.exeOp === ExeInst.Op.sc,
  //     selectedIn.exeOp === ExeInst.Op.st_w,
  //     selectedIn.exeOp === ExeInst.Op.st_h,
  //     selectedIn.exeOp === ExeInst.Op.st_b
  //   )
  // )

  resultOutReg.bits.instInfo.branchSetPort := PcSetPort.default

  if (supportBranchCsr) {

    val branchEnableFlag = RegInit(true.B)

    val branchSetPort           = io.peer.get.branchSetPort.get
    val csrScoreboardChangePort = io.peer.get.csrScoreboardChangePort.get

    // branch set
    branchSetPort    := PcSetPort.default
    branchSetPort.en := alu.io.result.jumpBranchInfo.en && branchEnableFlag
    when(alu.io.result.jumpBranchInfo.en) {
      branchEnableFlag := false.B
    }
    branchSetPort.pcAddr         := alu.io.result.jumpBranchInfo.pcAddr
    csrScoreboardChangePort.en   := selectedIn.instInfo.needCsr
    csrScoreboardChangePort.addr := selectedIn.instInfo.csrWritePort.addr

    val isErtn = WireDefault(selectedIn.exeOp === ExeInst.Op.ertn)
    val isIdle = WireDefault(selectedIn.exeOp === ExeInst.Op.idle)
    when(isIdle) {
      branchSetPort.isIdle := true.B
      branchSetPort.en     := branchEnableFlag
      branchSetPort.pcAddr := selectedIn.instInfo.pc + 4.U
      branchEnableFlag     := false.B
    }.elsewhen(isErtn) {
      branchSetPort.en     := branchEnableFlag
      branchSetPort.pcAddr := io.peer.get.csr.era.pc
      branchEnableFlag     := false.B
    }

    resultOutReg.bits.instInfo.branchSetPort := branchSetPort

    when(io.isFlush) {
      branchEnableFlag := true.B
    }

  }
}
