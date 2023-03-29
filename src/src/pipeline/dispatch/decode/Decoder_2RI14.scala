package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.DecodePort
import spec._
import spec.Inst.{_2RI14 => Inst}
import pipeline.dispatch.bundles.DecodeOutNdPort

class Decoder_2RI14 extends Decoder {

  io.out := DecodeOutNdPort.default

  val opcode = WireDefault(io.instInfoPort.inst(31, 24))
  val imm14  = WireDefault(io.instInfoPort.inst(23, 10))
  val rj     = WireDefault(io.instInfoPort.inst(9, 5))
  val rd     = WireDefault(io.instInfoPort.inst(4, 0))

  def outInfo = io.out.info

  // It has immediate
  io.out.info.isHasImm := false.B

  // Extend immediate
  val immSext = Wire(SInt(Width.Reg.data))
  val immZext = Wire(UInt(Width.Reg.data))
  immSext := imm14.asSInt
  immZext := imm14

  // Read and write GPR
  io.out.info.gprReadPorts(0).en   := true.B
  io.out.info.gprReadPorts(0).addr := rj
  io.out.info.gprReadPorts(1).en   := false.B
  io.out.info.gprReadPorts(1).addr := DontCare
  io.out.info.gprWritePort.en      := false.B
  io.out.info.gprWritePort.addr    := DontCare

  // Fallback
  io.out.info.exeSel         := ExeInst.Sel.none
  io.out.info.exeOp          := ExeInst.Op.nop
  io.out.info.imm            := DontCare
  io.out.isMatched           := false.B
  io.out.info.jumpBranchAddr := DontCare

  switch(opcode) {
    is(Inst.ll) {
      io.out.isMatched          := true.B
      outInfo.exeOp             := ExeInst.Op.ll
      outInfo.exeSel            := ExeInst.Sel.loadStore
      outInfo.gprWritePort.en   := true.B
      outInfo.gprWritePort.addr := rd
      outInfo.loadStoreImm      := immSext.asUInt
    }
    is(Inst.sc) {
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.sc
      outInfo.exeSel               := ExeInst.Sel.loadStore
      outInfo.gprReadPorts(1).en   := true.B
      outInfo.gprReadPorts(1).addr := rd
      outInfo.loadStoreImm         := immSext.asUInt
    }
  }
}
