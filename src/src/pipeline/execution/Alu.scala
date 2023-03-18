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

    val stallRequest = Output(Bool())
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
      shift := lop << rop(4,0);
    }
    is(Op.srl) {
      shift := lop >> rop(4,0);
    }
    is(Op.sra) {
      shift := (lop.asSInt << rop(4,0)).asUInt
    }
  }

  // val isMul = WireDefault(ExeInst.Op.mul === io.aluInst.op)
  // val isMulh = WireDefault(ExeInst.Op.mulh === io.aluInst.op)
  // val isMulhu = WireDefault(ExeInst.Op.mulhu === io.aluInst.op)

  // val mulStart = isMul & isMulh & isMulhu


  val useMul = WireDefault(VecInit(
    ExeInst.Op.mul,
    ExeInst.Op.mulh,
    ExeInst.Op.mulhu
  ).contains(io.aluInst.op)) 

  val mulStart = Wire(Bool())

  val mulStage = Module(new Mul)
  mulStage.io.mulInst.valid := mulStart
  mulStage.io.mulInst.bits.op := io.aluInst.op
  mulStage.io.mulInst.bits.leftOperand := lop
  mulStage.io.mulInst.bits.rightOperand := rop

  mulStage.io.mulResult.ready := DontCare

  mulStart := useMul & ~mulStage.io.mulResult.valid

  val mulResult = WireDefault(mulStage.io.mulResult.bits)

  // Div

  // val isDiv = WireDefault(ExeInst.Op.div === io.aluInst.op)
  // val isDivu = WireDefault(ExeInst.Op.divu === io.aluInst.op)
  // val isMod = WireDefault(ExeInst.Op.mod === io.aluInst.op)
  // val isModu = WireDefault(ExeInst.Op.modu === io.aluInst.op)

  // val divStart = isDiv & isDivu & isMod & isModu

  val useDiv = WireDefault(VecInit(
    ExeInst.Op.div,
    ExeInst.Op.divu,
    ExeInst.Op.mod,
    ExeInst.Op.modu
  ).contains(io.aluInst.op))

  val divStart = Wire(Bool()) 

  val divStage = Module(new Div)
  divStage.io.divInst.valid := divStart
  divStage.io.divInst.bits.op := io.aluInst.op
  divStage.io.divInst.bits.leftOperand := lop
  divStage.io.divInst.bits.rightOperand := rop

  divStage.io.divResult.ready := DontCare

  divStart := (useDiv & ~divStage.io.isRunning & ~divStage.io.divResult.valid)

  val quotient = WireDefault(divStage.io.divResult.bits.quotient)
  val remainder = WireDefault(divStage.io.divResult.bits.remainder)

  
  io.stallRequest := (mulStart | divStart | divStage.io.isRunning)

  when(~io.stallRequest) {
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
      is(Op.mul) {
        arithmetic := mulResult(wordLength-1, 0)
      }
      is(Op.mulh, Op.mulhu) {
        arithmetic := mulResult(doubleWordLength-1, wordLength)
      }
      is(Op.div, Op.divu) {
        arithmetic := quotient
      }
      is(Op.mod, Op.modu) {
        arithmetic := remainder
      }
    }
  }.otherwise{
    arithmetic := zeroWord
  }
  
  
}
