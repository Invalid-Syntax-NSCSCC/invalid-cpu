package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.DecodePort
import spec._
import spec.Inst.{_2RI12 => Inst}

class Decoder_2RI12 extends Decoder {
  val opcode = io.inst(31, 22)
  val imm12  = io.inst(21, 10)
  val rj     = io.inst(9, 5)
  val rd     = io.inst(4, 0)

  // It has immediate
  io.out.isHasImm := true.B

  // Extend immediate
  val immSext = Wire(SInt(Width.Reg.data))
  val immZext = Wire(UInt(Width.Reg.data))
  immSext := imm12.asSInt
  immZext := imm12

  // Read and write GPR
  io.out.regFileReadPorts(0).en   := true.B
  io.out.regFileReadPorts(0).addr := rj
  io.out.regFileReadPorts(1).en   := false.B
  io.out.regFileReadPorts(1).addr := DontCare
  io.out.regFileWritePort.en := true.B
  io.out.regFileWritePort.addr := rd

  // Fallback
  io.out.exeSel := ExeInst.Sel.none
  io.out.exeOp := ExeInst.Op.nop
  io.out.imm := DontCare
  io.out.isMatched := false.B

  switch(opcode) {
    is(Inst.addi_w) {
      io.out.isMatched := true.B
      io.out.exeSel := ExeInst.Sel.arithmetic
      io.out.exeOp := ExeInst.Op.add
      io.out.imm := immSext
    }
  }
}
