package pipeline.execution

import chisel3._
import chisel3.util._
import pipeline.execution.bundles.{AluInstNdPort, AluResultNdPort, JumpBranchInfoNdPort}
import spec.ExeInst.Op
import spec._

class Alu extends Module {
  val io = IO(new Bundle {
    val inputValid = Input(Bool())
    val aluInst    = Input(new AluInstNdPort)

    val outputValid = Output(Bool())
    val result      = Output(new AluResultNdPort)

    val isFlush = Input(Bool())
  })

  io.outputValid := true.B
  io.result      := AluResultNdPort.default

  def lop = io.aluInst.leftOperand

  def rop = io.aluInst.rightOperand

  /** Result definition
    */

  val logic = WireDefault(zeroWord)

  val shift = WireDefault(zeroWord)

  val jumpBranchInfo = WireDefault(JumpBranchInfoNdPort.default)

  // computed with one cycle
  val arithmetic = WireDefault(zeroWord)

  // Fallback
  io.result.arithmetic     := arithmetic
  io.result.logic          := logic
  io.result.jumpBranchInfo := jumpBranchInfo
  io.result.shift          := shift

  /** Logic computation
    */
  switch(io.aluInst.op) {
    is(Op.nor) {
      logic := ~(lop | rop)
    }
    is(Op.and) {
      logic := lop & rop
    }
    is(Op.or) {
      logic := lop | rop
    }
    is(Op.xor) {
      logic := lop ^ rop
    }
  }

  /** shift computation
    */
  switch(io.aluInst.op) {
    is(Op.sll) {
      shift := lop << rop(4, 0)
    }
    is(Op.srl) {
      shift := lop >> rop(4, 0)
    }
    is(Op.sra) {
      shift := (lop.asSInt >> rop(4, 0)).asUInt
    }
  }

  /** jump and branch computation
    */
  switch(io.aluInst.op) {
    is(Op.tlbfill, Op.tlbrd, Op.tlbwr, Op.tlbsrch) {
      jumpBranchInfo.en     := true.B
      jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
    }
    is(Op.b, Op.bl) {
      jumpBranchInfo.en     := true.B
      jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
    }
    is(Op.jirl) {
      jumpBranchInfo.en     := true.B
      jumpBranchInfo.pcAddr := lop + io.aluInst.jumpBranchAddr
    }
    is(Op.beq) {
      when(lop === rop) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.bne) {
      when(lop =/= rop) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.blt) {
      when(lop.asSInt < rop.asSInt) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.bge) {
      when(lop.asSInt >= rop.asSInt) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.bltu) {
      when(lop < rop) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.bgeu) {
      when(lop >= rop) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
  }

  /** arithmetic computation
    */

  // mul

  val useSignedMul = WireDefault(
    VecInit(
      ExeInst.Op.mul,
      ExeInst.Op.mulh
    ).contains(io.aluInst.op)
  )

  val useUnsignedMul = WireDefault(io.aluInst.op === ExeInst.Op.mulhu)

  val useMul = WireDefault(useSignedMul || useUnsignedMul)

  val mulResultValidReg = RegInit(false.B)
  when(useMul) {
    mulResultValidReg := !mulResultValidReg
  }

  val mulResult = RegNext(
    Mux(
      useSignedMul,
      (lop.asSInt * rop.asSInt).asUInt,
      lop * rop
    ),
    0.U(doubleWordLength.W)
  )

  // Div

  val useDiv = WireDefault(
    VecInit(
      ExeInst.Op.div,
      ExeInst.Op.divu,
      ExeInst.Op.mod,
      ExeInst.Op.modu
    ).contains(io.aluInst.op)
  )

  val divStage = Module(new NewDiv)

  val divisorValid = WireDefault(rop =/= 0.U)

  val divStart = WireDefault(useDiv && divisorValid)

  divStage.io.isFlush       := io.isFlush
  divStage.io.divInst.valid := divStart
  divStage.io.divInst.bits.isSigned := VecInit(
    ExeInst.Op.div,
    ExeInst.Op.mod
  ).contains(io.aluInst.op)
  divStage.io.divInst.bits.leftOperand  := lop
  divStage.io.divInst.bits.rightOperand := rop

  val quotient  = WireDefault(divStage.io.divResult.bits.quotient)
  val remainder = WireDefault(divStage.io.divResult.bits.remainder)

  io.outputValid := mulResultValidReg || !useMul && (
    !(divStart && !divStage.io.divResult.valid)
  )

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
      arithmetic := mulResult(wordLength - 1, 0)
    }
    is(Op.mulh, Op.mulhu) {
      arithmetic := mulResult(doubleWordLength - 1, wordLength)
    }
    is(Op.div, Op.divu) {
      arithmetic := divStage.io.divResult.bits.quotient
    }
    is(Op.mod, Op.modu) {
      arithmetic := divStage.io.divResult.bits.remainder
    }
  }

  when(io.isFlush) {
    io.outputValid    := false.B
    mulResult         := 0.U
    mulResultValidReg := false.B
  }
}
