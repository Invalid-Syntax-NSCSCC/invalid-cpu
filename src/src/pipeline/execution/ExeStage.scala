package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import spec.ExeInst.Sel
import spec._
import control.bundles.PipelineControlNdPort
import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals._
import spec.Param.{ExeStageState => State}
import pipeline.execution.Alu
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.execution.bundles.JumpBranchInfoNdPort
import common.bundles.PcSetPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import common.enums.ReadWriteSel
import control.csrRegsBundles.LlbctlBundle
import pipeline.mem.bundles.MemRequestNdPort
import pipeline.execution.bundles.ExeResultPort
import pipeline.common.BaseStage
import pipeline.mem.AddrTransNdPort

import scala.collection.immutable

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

class ExePeerPort extends Bundle {
  // `ExeStage` -> `Cu` (no delay)
  val branchSetPort = Output(new PcSetPort)
  val csr = Input(new Bundle {
    val llbctl = new LlbctlBundle
  })
}

// throw exception: 地址未对齐 ale
class ExeStage
    extends BaseStage(
      new ExeNdPort,
      new AddrTransNdPort,
      ExeNdPort.default,
      Some(new ExePeerPort)
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

  // 指令未对齐
  val isAle = WireDefault(false.B)

  def csrWriteData = resultOutReg.bits.instInfo.csrWritePort.data

  resultOutReg.bits.instInfo.exceptionRecords(Csr.ExceptionIndex.ale) := isAle
  resultOutReg.bits.instInfo.exceptionRecords(Csr.ExceptionIndex.sys) := selectedIn.exeOp === ExeInst.Op.syscall
  resultOutReg.bits.instInfo.exceptionRecords(Csr.ExceptionIndex.brk) := selectedIn.exeOp === ExeInst.Op.break_

  switch(selectedIn.exeOp) {
    is(ExeInst.Op.csrwr) {
      csrWriteData := selectedIn.csrData
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
      csrWriteData := gprWriteDataVec.asUInt
    }
  }

  /** MemAccess
    */

  val loadStoreAddr = WireDefault(selectedIn.leftOperand + selectedIn.loadStoreImm)
  val memReadEn = WireDefault(
    VecInit(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.ld_w, ExeInst.Op.ll)
      .contains(selectedIn.exeOp)
  )
  val memWriteEn = WireDefault(
    VecInit(ExeInst.Op.st_b, ExeInst.Op.st_h, ExeInst.Op.st_w, ExeInst.Op.sc)
      .contains(selectedIn.exeOp)
  )
  val memLoadUnsigned = WireDefault(VecInit(ExeInst.Op.ld_bu, ExeInst.Op.ld_hu).contains(selectedIn.exeOp))

  resultOutReg.bits.memRequest.isValid         := (memReadEn || memWriteEn) && !isAle
  resultOutReg.bits.memRequest.addr            := Cat(loadStoreAddr(wordLength - 1, 2), 0.U(2.W))
  resultOutReg.bits.memRequest.write.data      := selectedIn.rightOperand
  resultOutReg.bits.memRequest.read.isUnsigned := memLoadUnsigned
  resultOutReg.bits.memRequest.rw              := Mux(memWriteEn, ReadWriteSel.write, ReadWriteSel.read)
  // mask
  val maskEncode = loadStoreAddr(1, 0)
  switch(selectedIn.exeOp) {
    is(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.st_b) {
      resultOutReg.bits.memRequest.mask := Mux(
        maskEncode(1),
        Mux(maskEncode(0), "b1000".U, "b0100".U),
        Mux(maskEncode(0), "b0010".U, "b0001".U)
      )
    }
    is(ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.st_h) {
      when(maskEncode(0)) {
        isAle := true.B // 未对齐
      }
      resultOutReg.bits.memRequest.mask := Mux(maskEncode(1), "b1100".U, "b0011".U)
    }
    is(ExeInst.Op.ld_w, ExeInst.Op.ll, ExeInst.Op.st_w, ExeInst.Op.sc) {
      isAle                             := maskEncode.orR
      resultOutReg.bits.memRequest.mask := "b1111".U
    }
  }
  switch(selectedIn.exeOp) {
    is(ExeInst.Op.st_b) {
      resultOutReg.bits.memRequest.write.data := Cat(
        Seq.fill(wordLength / byteLength)(selectedIn.rightOperand(byteLength - 1, 0))
      )
    }
    is(ExeInst.Op.st_h) {
      resultOutReg.bits.memRequest.write.data := Cat(Seq.fill(2)(selectedIn.rightOperand(wordLength / 2 - 1, 0)))
    }
  }

  resultOutReg.bits.instInfo.load.en := Cat(
    0.U(2.W),
    selectedIn.exeOp === ExeInst.Op.ll,
    selectedIn.exeOp === ExeInst.Op.ld_w,
    selectedIn.exeOp === ExeInst.Op.ld_hu,
    selectedIn.exeOp === ExeInst.Op.ld_h,
    selectedIn.exeOp === ExeInst.Op.ld_bu,
    selectedIn.exeOp === ExeInst.Op.ld_b
  ) & !isAle
  resultOutReg.bits.instInfo.store.en := Cat(
    0.U(4.W),
    io.peer.get.csr.llbctl.wcllb &&
      selectedIn.exeOp === ExeInst.Op.sc,
    selectedIn.exeOp === ExeInst.Op.st_w,
    selectedIn.exeOp === ExeInst.Op.st_h,
    selectedIn.exeOp === ExeInst.Op.st_b
  ) & !isAle
  resultOutReg.bits.instInfo.load.vaddr  := loadStoreAddr
  resultOutReg.bits.instInfo.store.vaddr := loadStoreAddr
  resultOutReg.bits.instInfo.store.data := MuxLookup(selectedIn.exeOp, selectedIn.rightOperand)(
    immutable.Seq(
      ExeInst.Op.st_b -> Mux(
        maskEncode(1),
        Mux(
          maskEncode(0),
          Cat(selectedIn.rightOperand(7, 0), 0.U(24.W)),
          Cat(selectedIn.rightOperand(7, 0), 0.U(16.W))
        ),
        Mux(maskEncode(0), Cat(selectedIn.rightOperand(7, 0), 0.U(8.W)), selectedIn.rightOperand(7, 0))
      ),
      ExeInst.Op.st_h -> Mux(
        maskEncode(1),
        Cat(selectedIn.rightOperand(15, 0), 0.U(16.W)),
        selectedIn.rightOperand(15, 0)
      )
    )
  )

  // branch set
  io.peer.get.branchSetPort := alu.io.result.jumpBranchInfo
}
