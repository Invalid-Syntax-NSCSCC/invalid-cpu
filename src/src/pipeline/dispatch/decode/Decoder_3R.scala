package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import spec._
import spec.Inst.{_3R => Inst}

class Decoder_3R extends Decoder {
  // TODO: Fix bug where `isMatched` is always true

  io.out.isMatched := false.B

  val opcode = WireDefault(io.instInfoPort.inst(31, 15))
  val rk     = WireDefault(io.instInfoPort.inst(14, 10))
  val rj     = WireDefault(io.instInfoPort.inst(9, 5))
  val rd     = WireDefault(io.instInfoPort.inst(4, 0))
  val ui5    = WireDefault(rk)

  def outInfo = io.out.info

  outInfo.isHasImm := false.B

  outInfo.gprReadPorts(0).en   := true.B
  outInfo.gprReadPorts(0).addr := rj
  outInfo.gprReadPorts(1).en   := true.B
  outInfo.gprReadPorts(1).addr := rk
  outInfo.gprWritePort.en      := true.B
  outInfo.gprWritePort.addr    := rd

  // Fallback
  io.out.info.exeSel         := ExeInst.Sel.none
  io.out.info.exeOp          := ExeInst.Op.nop
  io.out.info.imm            := DontCare
  io.out.isMatched           := false.B
  io.out.info.jumpBranchAddr := DontCare
  io.out.info.pcAddr         := DontCare

  switch(opcode) {
    is(Inst.add_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.add
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.sub_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.sub
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.slt_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.slt
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.sltu_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.sltu
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.nor_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.nor
      outInfo.exeSel   := ExeInst.Sel.logic
    }
    is(Inst.and_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.and
      outInfo.exeSel   := ExeInst.Sel.logic
    }
    is(Inst.or_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.or
      outInfo.exeSel   := ExeInst.Sel.logic
    }
    is(Inst.xor_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.xor
      outInfo.exeSel   := ExeInst.Sel.logic
    }
    is(Inst.sll_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.sll
      outInfo.exeSel   := ExeInst.Sel.shift
    }
    is(Inst.srl_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.srl
      outInfo.exeSel   := ExeInst.Sel.shift
    }
    is(Inst.sra_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.sra
      outInfo.exeSel   := ExeInst.Sel.shift
    }
    is(Inst.mul_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.mul
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.mulh_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.mul
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.mulh_wu) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.mulhu
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.div_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.div
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.div_wu) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.divu
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.mod_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.mod
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.mod_wu) {
      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.modu
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.slli_w) {
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.sll
      outInfo.exeSel               := ExeInst.Sel.shift
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.isHasImm             := true.B
      outInfo.imm                  := ui5
    }
    is(Inst.srli_w) {
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.srl
      outInfo.exeSel               := ExeInst.Sel.shift
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.isHasImm             := true.B
      outInfo.imm                  := ui5
    }
    is(Inst.srai_w) {
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.sra
      outInfo.exeSel               := ExeInst.Sel.shift
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.isHasImm             := true.B
      outInfo.imm                  := ui5
    }
  }
}
