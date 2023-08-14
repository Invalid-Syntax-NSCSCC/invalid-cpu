package pipeline.simple

import chisel3._
import chisel3.util._
import common.BaseStage
import pipeline.simple.bundles.{RegWakeUpNdPort, WbNdPort}
import pipeline.simple.execution.Alu
import spec.ExeInst.OpBundle

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
  alu.io.aluInst.op             := selectedIn.instInfo.exeOp
  alu.io.aluInst.leftOperand    := selectedIn.leftOperand
  alu.io.aluInst.rightOperand   := selectedIn.rightOperand
  alu.io.aluInst.jumpBranchAddr := DontCare

  out.gprWrite.data := DontCare

  switch(selectedIn.instInfo.exeOp.sel) {
    is(OpBundle.sel_arthOrLogic) {
      out.gprWrite.data := alu.io.result.logic
    }
    is(OpBundle.sel_mulDiv) {
      out.gprWrite.data := alu.io.result.mulDiv
    }
    is(OpBundle.sel_readTimeOrShift) {
      out.gprWrite.data := alu.io.result.shift
    }
  }
}
