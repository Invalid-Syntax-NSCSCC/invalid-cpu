package pipeline.execution

import chisel3._
import chisel3.util._
import pipeline.execution.bundles.{AluInstNdPort, AluResultNdPort}
import spec._
import ExeInst.Op

class Alu extends Module {
  val io = IO(new Bundle {
    val aluInst = Input(new AluInstNdPort)
    val result  = Output(new AluResultNdPort)

    val advance = Output(Bool())
  })

  io.result := DontCare

  def lop = io.aluInst.leftOperand
  def rop = io.aluInst.rightOperand
  def arithmetic = io.result.arithmetic
  def logic = io.result.logic
  def shift = io.result.shift


  // Logic computation
  switch(io.aluInst.op) {
    is (Op.nor) {
      logic := ~(lop | rop)
    }
    is(Op.and) {
      logic := lop & rop
    }
    is(Op.or) {
      logic := lop | rop
    }
    is (Op.xor) {
      logic := lop ^ rop
    }
  }

  // shift computation
  switch(io.aluInst.op) {
    is(Op.sll) {
      shift := lop << rop;
    }
    is(Op.srl) {
      shift := lop >> rop;
    }
    is(Op.sra) {
      shift := (lop.asSInt << rop).asUInt
    }
  }

  // Arithmetic computation
  switch(io.aluInst.op) {
    is(Op.add) {
      arithmetic := (lop.asSInt + rop.asSInt).asUInt
    }
    is(Op.sub) {
      arithmetic := (lop.asSInt - rop.asSInt).asUInt
    }
    is(Op.slt) {
      arithmetic := (lop.asSInt < rop.asSInt).asUInt
    }
    is(Op.sltu) {
      arithmetic := (lop < rop).asUInt
    }
  }


  // val mulResIsHigh = WireDefault(VecInit(
  //   ExeInst.Op.mulh,
  //   ExeInst.Op.mulhu
  // ).contains(io.aluInst.op))

  // val mulSign = WireDefault(VecInit(
  //   ExeInst.Op.mul,
  //   ExeInst.Op.mulh
  // ).contains(io.aluInst.op))

  val mulStart = WireDefault(VecInit(
    ExeInst.Op.mul,
    ExeInst.Op.mulh,
    ExeInst.Op.mulhu
  ).contains(io.aluInst.op))

  val mulStage = Module(new Mul)
  mulStage.io.mulInst.valid := mulStart
  mulStage.io.mulInst.bits.op := io.aluInst.op
  mulStage.io.mulInst.bits.leftOperand := lop
  mulStage.io.mulInst.bits.rightOperand := rop

  val divStart = WireDefault(VecInit(
    ExeInst.Op.div,
    ExeInst.Op.divu,
    ExeInst.Op.mod,
    ExeInst.Op.divu
  ).contains(io.aluInst.op))

  val divStage = Module(new Div)
  divStage.io.divInst.valid := divStart
  divStage.io.divInst.bits.op := io.aluInst.op
  divStage.io.divInst.bits.leftOperand := lop
  divStage.io.divInst.bits.rightOperand := rop

  io.advance := ~(mulStage.io.isRunning & divStage.io.isRunning)

  
  
}
