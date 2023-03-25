package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.DecodePort
import spec._
import spec.Inst.{_special => Inst}

class Decoder_special extends Decoder {

  val rd = WireDefault(io.instInfoPort.inst(4, 0))

  val opcode7 = WireDefault(io.instInfoPort.inst(31, 25))
  val imm20   = WireDefault(io.instInfoPort.inst(24, 5))

  val opcode17 = WireDefault(io.instInfoPort.inst(31, 15))
  val hint = WireDefault(io.instInfoPort.inst(14,0))

  def outInfo = io.out.info

  io.out := DontCare
  // It has immediate
  io.out.info.isHasImm := true.B

  // Extend immediate
  val immSext = Wire(SInt(Width.Reg.data))
  val immZext = Wire(UInt(Width.Reg.data))
  immSext := zeroWord.asSInt
  immZext := zeroWord

  // Read and write GPR
  io.out.info.gprReadPorts(0).en   := false.B
  io.out.info.gprReadPorts(0).addr := DontCare
  io.out.info.gprReadPorts(1).en   := false.B
  io.out.info.gprReadPorts(1).addr := DontCare
  io.out.info.gprWritePort.en      := false.B
  io.out.info.gprWritePort.addr    := rd

  // Fallback
  io.out.info.exeSel         := ExeInst.Sel.none
  io.out.info.exeOp          := ExeInst.Op.nop
  io.out.info.imm            := DontCare
  io.out.isMatched           := false.B
  io.out.info.jumpBranchAddr := DontCare
  io.out.info.pcAddr         := DontCare

  switch(opcode7) {
    is(Inst.pcaddu12i) {
      // <=> 0 + imm
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.add
      outInfo.exeSel               := ExeInst.Sel.arithmetic
      outInfo.gprReadPorts(0).en   := true.B
      outInfo.gprReadPorts(0).addr := RegIndex.r0
      outInfo.gprWritePort.en      := true.B
      outInfo.isHasImm             := true.B
      immSext                      := (imm20 << 12).asSInt
      outInfo.imm                  := immSext.asUInt + io.instInfoPort.pcAddr
    }
    is(Inst.lu12i_w) {
      // <=> 0 + imm
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.add
      outInfo.exeSel               := ExeInst.Sel.arithmetic
      outInfo.gprReadPorts(0).en   := true.B
      outInfo.gprReadPorts(0).addr := RegIndex.r0
      outInfo.gprWritePort.en      := true.B
      outInfo.isHasImm             := true.B
      immSext                      := (imm20 << 12).asSInt
      outInfo.imm                  := immSext.asUInt
    }
    is(Inst.dbar) {
      io.out.isMatched := true.B
      outInfo.exeOp := ExeInst.Op.dbar
      outInfo.exeSel:= ExeInst.Sel.loadStore
      outInfo.isHasImm := true.B
      immZext := hint
      outInfo.imm := immZext
    }
    is(Inst.ibar) {
      io.out.isMatched := true.B
      outInfo.exeOp := ExeInst.Op.ibar
      outInfo.exeSel:= ExeInst.Sel.loadStore
      outInfo.isHasImm := true.B
      immZext := hint
      outInfo.imm := immZext
    }
  
  }
}
