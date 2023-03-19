package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.DecodePort
import spec._
import spec.Inst.{_2RI12 => Inst}

class Decoder_2RI12 extends Decoder {
  val opcode = WireDefault(io.inst(31, 22))
  val imm12  = WireDefault(io.inst(21, 10))
  val rj     = WireDefault(io.inst(9, 5))
  val rd     = WireDefault(io.inst(4, 0))
  
  io.out := DontCare
  // It has immediate
  io.out.info.isHasImm := true.B

  // Extend immediate
  val immSext = Wire(SInt(Width.Reg.data))
  val immZext = Wire(UInt(Width.Reg.data))
  immSext := imm12.asSInt
  immZext := imm12

  // Read and write GPR
  io.out.info.gprReadPorts(0).en   := true.B
  io.out.info.gprReadPorts(0).addr := rj
  io.out.info.gprReadPorts(1).en   := false.B
  io.out.info.gprReadPorts(1).addr := DontCare
  io.out.info.gprWritePort.en      := true.B
  io.out.info.gprWritePort.addr    := rd

  // Fallback
  io.out.info.exeSel := ExeInst.Sel.none
  io.out.info.exeOp  := ExeInst.Op.nop
  io.out.info.imm    := DontCare
  io.out.isMatched   := false.B

  switch(opcode) {
    is(Inst.addi_w) {
      io.out.isMatched   := true.B
      io.out.info.exeSel := ExeInst.Sel.arithmetic
      io.out.info.exeOp  := ExeInst.Op.add
      io.out.info.imm    := immSext.asUInt
    }
  }
}
