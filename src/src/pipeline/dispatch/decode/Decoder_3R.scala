package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import spec._
import spec.Inst.{_3R => Inst}

class Docoder_3R extends Decoder {
  io.out := DontCare

  val opcode = WireDefault(io.inst(31,15))
  val rk = WireDefault(io.inst(14,10))
  val rj = WireDefault(io.inst(9,5))
  val rd = WireDefault(io.inst(4,0))

  io.out.info.isHasImm := false.B

  io.out.info.gprReadPorts(0).en   := true.B
  io.out.info.gprReadPorts(0).addr := rj
  io.out.info.gprReadPorts(1).en   := true.B
  io.out.info.gprReadPorts(1).addr := rk
  io.out.info.gprWritePort.en      := true.B
  io.out.info.gprWritePort.addr    := rd

  def outInfo = io.out.info

  switch(opcode) {
    is(Inst.add_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.add
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.sub_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.sub
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.slt_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.slt
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.sltu_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.sltu
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.nor_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.nor
        outInfo.exeSel := ExeInst.Sel.logic
    }
    is(Inst.and_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.and
        outInfo.exeSel := ExeInst.Sel.logic
    }
    is(Inst.or_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.or
        outInfo.exeSel := ExeInst.Sel.logic
    }
    is(Inst.xor_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.xor
        outInfo.exeSel := ExeInst.Sel.logic
    }
    is(Inst.sll_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.sll
        outInfo.exeSel := ExeInst.Sel.shift
    }
    is(Inst.srl_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.srl
        outInfo.exeSel := ExeInst.Sel.shift
    }
    is(Inst.sra_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.sra
        outInfo.exeSel := ExeInst.Sel.shift
    }
    is(Inst.mul_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.mul
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.mulh_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.mul
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.mulh_wu) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.mulhu
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.div_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.div
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.div_wu) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.divu
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.mod_w) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.mod
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
    is(Inst.mod_wu) {
        io.out.isMatched := true.B
        outInfo.exeOp := ExeInst.Op.modu
        outInfo.exeSel := ExeInst.Sel.arithmetic
    }
  }
}
