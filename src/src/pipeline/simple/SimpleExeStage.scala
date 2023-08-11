package pipeline.simple

import chisel3._
import chisel3.util._
import spec.ExeInst.Sel
import common.BaseStage
import _root_.execution.Alu
import pipeline.simple.ExeNdPort
import pipeline.simple.bundles.WbNdPort
import pipeline.simple.bundles.RegWakeUpNdPort

class SimpleExeStage
    extends BaseStage(
      new ExeNdPort,
      new WbNdPort,
      ExeNdPort.default,
      Some(new RegWakeUpNdPort)
    ) {
  val out      = Wire(new WbNdPort)
  val outValid = Wire(Bool())
  resultOutReg.bits  := out
  resultOutReg.valid := outValid

  val peer = io.peer.get
  peer.en    := outValid && selectedIn.gprWritePort.en
  peer.addr  := selectedIn.gprWritePort.addr
  peer.data  := out.gprWrite.data
  peer.robId := selectedIn.instInfo.robId

  // ALU module
  val alu = Module(new Alu)

  isComputed        := alu.io.outputValid
  out               := DontCare
  out.instInfo      := selectedIn.instInfo
  out.gprWrite.en   := selectedIn.gprWritePort.en
  out.gprWrite.addr := selectedIn.gprWritePort.addr
  outValid          := isComputed && selectedIn.instInfo.isValid

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
