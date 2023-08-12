package pipeline.simple.execution

import chisel3._
import chisel3.util._
import spec._
import spec.ExeInst.OpBundle
import execution.Mul
import execution.Div

class Alu extends Module {
  val io = IO(new Bundle {
    val inputValid = Input(Bool())
    val aluInst = Input(new Bundle {
      val op             = new OpBundle
      val leftOperand    = UInt(Width.Reg.data)
      val rightOperand   = UInt(Width.Reg.data)
      val jumpBranchAddr = UInt(Width.Reg.data)
    })

    val outputValid = Output(Bool())
    val result = Output(new Bundle {
      val shift  = UInt(Width.Reg.data)
      val logic  = UInt(Width.Reg.data)
      val mulDiv = UInt(Width.Reg.data)
      val jumpEn = Bool()
    })

    val isFlush = Input(Bool())
  })

  io.outputValid   := false.B
  io.result        := DontCare
  io.result.jumpEn := false.B

  val sel   = io.aluInst.op.sel
  val subOp = io.aluInst.op.subOp
  val lop   = io.aluInst.leftOperand
  val rop   = io.aluInst.rightOperand

  val shift = io.result.shift
  val logic = io.result.logic

  switch(subOp) {
    is(OpBundle.sll.subOp) {
      shift := lop << rop(4, 0)
    }
    is(OpBundle.srl.subOp) {
      shift := lop >> rop(4, 0)
    }
    is(OpBundle.sra.subOp) {
      shift := (lop.asSInt >> rop(4, 0)).asUInt
    }
  }

  switch(subOp) {
    is(OpBundle.nor.subOp) {
      logic := ~(lop | rop)
    }
    is(OpBundle.and.subOp) {
      logic := lop & rop
    }
    is(OpBundle.or.subOp) {
      logic := lop | rop
    }
    is(OpBundle.xor.subOp) {
      logic := lop ^ rop
    }
    is(OpBundle.add.subOp) {
      logic := (lop.asSInt + rop.asSInt).asUInt
    }
    is(OpBundle.sub.subOp) {
      logic := (lop.asSInt - rop.asSInt).asUInt
    }
    is(OpBundle.slt.subOp) {
      logic := (lop.asSInt < rop.asSInt).asUInt
    }
    is(OpBundle.sltu.subOp) {
      logic := (lop < rop).asUInt
    }
  }

  // jump
  val simpleJumpEn = WireDefault(false.B)
  io.result.jumpEn := Mux(
    io.aluInst.op === OpBundle.jirl,
    true.B,
    simpleJumpEn
  )

  switch(subOp) {
    is(OpBundle.b.subOp, OpBundle.bl.subOp) {
      simpleJumpEn := true.B
    }
    is(OpBundle.beq.subOp) {
      simpleJumpEn := lop === rop
    }
    is(OpBundle.bne.subOp) {
      simpleJumpEn := lop =/= rop
    }
    is(OpBundle.blt.subOp) {
      simpleJumpEn := lop.asSInt < rop.asSInt
    }
    is(OpBundle.bge.subOp) {
      simpleJumpEn := lop.asSInt >= rop.asSInt
    }
    is(OpBundle.bltu.subOp) {
      simpleJumpEn := lop < rop
    }
    is(OpBundle.bgeu.subOp) {
      simpleJumpEn := lop >= rop
    }
  }

  // mul div

  val isMulDiv = sel === OpBundle.sel_mulDiv

  val mulStage = Module(new Mul)

  val useSignedMul = subOp === OpBundle.mulh.subOp

  val useUnsignedMul = subOp === OpBundle.mulhu.subOp || subOp === OpBundle.mul.subOp

  val useMul = (useSignedMul || useUnsignedMul) && isMulDiv

  val mulStart = useMul

  mulStage.io.isFlush                   := io.isFlush
  mulStage.io.mulInst.valid             := mulStart
  mulStage.io.mulInst.bits.isSigned     := useSignedMul
  mulStage.io.mulInst.bits.leftOperand  := lop
  mulStage.io.mulInst.bits.rightOperand := rop

  val mulResult = mulStage.io.mulResult.bits

  val useDiv = isMulDiv && (
    subOp === OpBundle.div.subOp ||
      subOp === OpBundle.divu.subOp ||
      subOp === OpBundle.mod.subOp ||
      subOp === OpBundle.modu.subOp
  )

  val divStage = Module(new Div)

  val divisorValid = WireDefault(rop =/= 0.U)

  val divStart = WireDefault(useDiv && divisorValid)

  divStage.io.isFlush                   := io.isFlush
  divStage.io.divInst.valid             := divStart
  divStage.io.divInst.bits.isSigned     := subOp === OpBundle.div.subOp || subOp === OpBundle.mod.subOp
  divStage.io.divInst.bits.leftOperand  := lop
  divStage.io.divInst.bits.rightOperand := rop

  val quotient  = WireDefault(divStage.io.divResult.bits.quotient)
  val remainder = WireDefault(divStage.io.divResult.bits.remainder)

  io.outputValid :=
    !(mulStart && !mulStage.io.mulResult.valid) && (
      !(divStart && !divStage.io.divResult.valid)
    )

  switch(subOp) {
    is(OpBundle.mul.subOp, OpBundle.mulh.subOp) {
      io.result.mulDiv := mulResult(wordLength - 1, 0)
    }
    is(OpBundle.mulhu.subOp) {
      io.result.mulDiv := mulResult(doubleWordLength - 1, wordLength)
    }
    is(OpBundle.div.subOp, OpBundle.divu.subOp) {
      io.result.mulDiv := divStage.io.divResult.bits.quotient
    }
    is(OpBundle.mod.subOp, OpBundle.modu.subOp) {
      io.result.mulDiv := divStage.io.divResult.bits.remainder
    }
  }

  when(io.isFlush) {
    io.outputValid := false.B
  }
}
