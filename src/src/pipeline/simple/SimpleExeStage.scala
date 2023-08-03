package pipeline.simple

import chisel3._
import chisel3.util._
import common.BaseStage
import execution.Alu

class SimpleExeStage
    extends BaseStage(
      new ExeNdPort,
      new DummyPort,
      ExeNdPort.default,
      None
    ) {
  val out  = io.out.bits
  val peer = io.peer.get

  // ALU module
  val alu = Module(new Alu)

  isComputed        := alu.io.outputValid
  out               := DontCare
  out.instInfo      := selectedIn.instInfo
  out.gprWrite.en   := selectedIn.gprWritePort.en
  out.gprWrite.addr := selectedIn.gprWritePort.addr
  io.out.valid      := isComputed && selectedIn.instInfo.isValid

  // alu

  // ALU input
  alu.io.isFlush                := io.isFlush
  alu.io.inputValid             := selectedIn.instInfo.isValid
  alu.io.aluInst.op             := selectedIn.exeOp
  alu.io.aluInst.leftOperand    := selectedIn.leftOperand
  alu.io.aluInst.rightOperand   := selectedIn.rightOperand
  alu.io.aluInst.jumpBranchAddr := DontCare

  out.gprWrite.data := DontCare

  switch(selectedIn.exeSel) {
    is(Sel.logic) {
      out.gprWrite.data := alu.io.result.logic
    }
    is(Sel.shift) {
      out.gprWrite.data := alu.io.result.shift
    }
    is(Sel.arithmetic) {
      out.gprWrite.data := alu.io.result.arithmetic
    }
  }
}
