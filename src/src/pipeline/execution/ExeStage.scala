package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ExeInstNdPort
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
import pipeline.mem.bundles.MemRequestNdPort
import pipeline.execution.bundles.ExeResultPort

// TODO: Add (flush ?) when jump / branch
// throw exception: 地址未对齐 ale
class ExeStage extends Module {
  val io = IO(new Bundle {
    val exeInstPort = Flipped(Decoupled(new ExeInstNdPort))

    // `ExeStage` -> `AddrTransStage` (next clock pulse)
    val exeResultPort = Decoupled(new ExeResultPort)

    // `ExeStage` -> `Pc` (no delay)
    val branchSetPort = Output(new PcSetPort)

    // Pipeline control signal
    // `Cu` -> `ExeStage`
    val pipelineControlPort = Input(new PipelineControlNdPort)
  })

  // ALU module
  val alu = Module(new Alu)

  // Pass to the next stage in a sequential way
  val outputValidReg = RegNext(alu.io.outputValid, false.B)
  io.exeInstPort.ready   := alu.io.outputValid
  io.exeResultPort.valid := outputValidReg
  val exeResultReg = RegInit(ExeResultPort.default)
  io.exeResultPort.bits := exeResultReg

  // Start: state machine

  val selectedExeInst = WireDefault(io.exeInstPort.bits)

  // ALU input
  alu.io.inputValid             := true.B
  alu.io.aluInst.op             := selectedExeInst.exeOp
  alu.io.aluInst.leftOperand    := selectedExeInst.leftOperand
  alu.io.aluInst.rightOperand   := selectedExeInst.rightOperand
  alu.io.aluInst.jumpBranchAddr := selectedExeInst.jumpBranchAddr // also load-store imm
  // alu.io.isBlocking             := isBlocking

  // ALU output

  // write-back information fallback
  exeResultReg.gprWritePort.en   := false.B
  exeResultReg.gprWritePort.addr := zeroWord
  exeResultReg.gprWritePort.data := zeroWord

  // write-back information selection
  exeResultReg.gprWritePort.en   := selectedExeInst.gprWritePort.en
  exeResultReg.gprWritePort.addr := selectedExeInst.gprWritePort.addr

  switch(selectedExeInst.exeSel) {
    is(Sel.logic) {
      // io.freePorts.en  := gprWriteReg.en
      exeResultReg.gprWritePort.data := alu.io.result.logic
    }
    is(Sel.shift) {
      // io.freePorts.en  := gprWriteReg.en
      exeResultReg.gprWritePort.data := alu.io.result.shift
    }
    is(Sel.arithmetic) {
      // io.freePorts.en  := gprWriteReg.en
      exeResultReg.gprWritePort.data := alu.io.result.arithmetic
    }
    is(Sel.jumpBranch) {
      // io.freePorts.en  := gprWriteReg.en
      exeResultReg.gprWritePort.data := selectedExeInst.instInfo.pc + 4.U
    }
  }

  switch(selectedExeInst.exeOp) {
    is(ExeInst.Op.csrrd) {
      // io.freePorts.en  := gprWriteReg.en
      exeResultReg.gprWritePort.data := selectedExeInst.csrData
    }
  }

  /** CsrWrite
    */

  // 指令未对齐
  val isAle = WireDefault(false.B)

  def csrWriteData = exeResultReg.instInfo.csrWritePort.data

  exeResultReg.instInfo.exceptionRecords(Csr.ExceptionIndex.ale) := isAle

  switch(selectedExeInst.exeOp) {
    is(ExeInst.Op.csrwr) {
      csrWriteData := selectedExeInst.csrData
    }
    is(ExeInst.Op.csrxchg) {
      // lop: write value  rop: mask
      val gprWriteDataVec = Wire(Vec(wordLength, Bool()))
      selectedExeInst.leftOperand.asBools
        .lazyZip(selectedExeInst.rightOperand.asBools)
        .lazyZip(selectedExeInst.csrData.asBools)
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
  val loadStoreAddr = WireDefault(selectedExeInst.leftOperand + selectedExeInst.loadStoreImm)
  val memReadEn = WireDefault(
    VecInit(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.ld_w, ExeInst.Op.ll)
      .contains(selectedExeInst.exeOp)
  )
  val memWriteEn = WireDefault(
    VecInit(ExeInst.Op.st_b, ExeInst.Op.st_h, ExeInst.Op.st_w, ExeInst.Op.sc)
      .contains(selectedExeInst.exeOp)
  )
  val memLoadUnsigned = WireDefault(VecInit(ExeInst.Op.ld_bu, ExeInst.Op.ld_hu).contains(selectedExeInst.exeOp))

  exeResultReg.memAccessPort.isValid         := (memReadEn || memWriteEn) && !isAle
  exeResultReg.memAccessPort.addr            := Cat(loadStoreAddr(wordLength - 1, 2), 0.U(2.W))
  exeResultReg.memAccessPort.write.data      := selectedExeInst.rightOperand
  exeResultReg.memAccessPort.read.isUnsigned := memLoadUnsigned
  exeResultReg.memAccessPort.rw              := Mux(memWriteEn, ReadWriteSel.write, ReadWriteSel.read)
  // mask
  val maskEncode = loadStoreAddr(1, 0)
  switch(selectedExeInst.exeOp) {
    is(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.st_b) {
      exeResultReg.memAccessPort.mask := Mux(
        maskEncode(1),
        Mux(maskEncode(0), "b1000".U, "b0100".U),
        Mux(maskEncode(0), "b0010".U, "b0001".U)
      )
    }
    is(ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.st_h) {
      when(maskEncode(0)) {
        isAle := true.B // 未对齐
      }
      exeResultReg.memAccessPort.mask := Mux(maskEncode(1), "b1100".U, "b0011".U)
    }
    is(ExeInst.Op.ld_w, ExeInst.Op.ll, ExeInst.Op.st_w, ExeInst.Op.sc) {
      isAle                           := maskEncode.orR
      exeResultReg.memAccessPort.mask := "b1111".U
    }
  }

  // branch set
  io.branchSetPort := alu.io.result.jumpBranchInfo

  /** InstInfo Csr read or write info
    */

  // Flush
  when(io.pipelineControlPort.flush) {
    exeResultReg.gprWritePort.en       := false.B
    exeResultReg.memAccessPort.isValid := false.B
    outputValidReg                     := false.B
    io.exeInstPort.ready               := false.B
    io.exeResultPort.valid             := false.B
  }
}
