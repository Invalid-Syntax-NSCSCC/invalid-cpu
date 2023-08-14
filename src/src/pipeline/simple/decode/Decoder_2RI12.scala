package pipeline.simple.decode

import chisel3._
import chisel3.util._
import pipeline.simple.decode.bundles.DecodeOutNdPort
import spec.ExeInst.OpBundle
import spec.Inst.{_2RI12 => Inst}
import spec._

class Decoder_2RI12 extends BaseDecoder {
  io.out := DecodeOutNdPort.default

  val opcode      = WireDefault(io.instInfoPort.inst(31, 22))
  val imm12       = WireDefault(io.instInfoPort.inst(21, 10))
  val rj          = WireDefault(io.instInfoPort.inst(9, 5))
  val rd          = WireDefault(io.instInfoPort.inst(4, 0))
  val rdIsNotZero = WireDefault(rd.orR)

  val outInfo = io.out.info

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
  io.out.info.gprWritePort.en      := rdIsNotZero // true.B
  io.out.info.gprWritePort.addr    := rd

  // Fallback
  io.out.info.exeOp          := OpBundle.nop
  io.out.info.imm            := DontCare
  io.out.isMatched           := false.B
  io.out.info.jumpBranchAddr := DontCare

  switch(opcode) {
    is(Inst.slti) {
      io.out.isMatched  := true.B
      io.out.info.exeOp := OpBundle.slt
      io.out.info.imm   := immSext.asUInt
    }
    is(Inst.sltui) {
      io.out.isMatched  := true.B
      io.out.info.exeOp := OpBundle.sltu
      io.out.info.imm   := immSext.asUInt
    }
    is(Inst.addi_w) {
      io.out.isMatched  := true.B
      io.out.info.exeOp := OpBundle.add
      io.out.info.imm   := immSext.asUInt
    }
    is(Inst.andi) {
      io.out.isMatched  := true.B
      io.out.info.exeOp := OpBundle.and
      io.out.info.imm   := immZext
    }
    is(Inst.ori) {
      io.out.isMatched  := true.B
      io.out.info.exeOp := OpBundle.or
      io.out.info.imm   := immZext
    }
    is(Inst.xori) {
      io.out.isMatched  := true.B
      io.out.info.exeOp := OpBundle.xor
      io.out.info.imm   := immZext
    }
    // LoadStore: read0: rj, read1: store reg src, loadStoreImm: offset
    is(Inst.ld_b) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      io.out.info.exeOp               := OpBundle.ld_b
      io.out.info.isHasImm            := false.B
      io.out.info.loadStoreImm        := immSext.asUInt

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.ld_h) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      io.out.info.exeOp               := OpBundle.ld_h
      io.out.info.isHasImm            := false.B
      io.out.info.loadStoreImm        := immSext.asUInt

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.ld_w) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      io.out.info.exeOp               := OpBundle.ld_w
      io.out.info.isHasImm            := false.B
      io.out.info.loadStoreImm        := immSext.asUInt

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.ld_bu) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      io.out.info.exeOp               := OpBundle.ld_bu
      io.out.info.isHasImm            := false.B
      io.out.info.loadStoreImm        := immSext.asUInt

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.ld_hu) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      io.out.info.exeOp               := OpBundle.ld_hu
      io.out.info.isHasImm            := false.B
      io.out.info.loadStoreImm        := immSext.asUInt

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.st_b) {
      io.out.info.isIssueMainPipeline  := true.B
      io.out.isMatched                 := true.B
      io.out.info.exeOp                := OpBundle.st_b
      io.out.info.isHasImm             := false.B
      io.out.info.loadStoreImm         := immSext.asUInt
      io.out.info.gprReadPorts(1).en   := true.B
      io.out.info.gprReadPorts(1).addr := rd
      io.out.info.gprWritePort.en      := false.B
      io.out.info.gprWritePort.addr    := DontCare

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.st_h) {
      io.out.info.isIssueMainPipeline  := true.B
      io.out.isMatched                 := true.B
      io.out.info.exeOp                := OpBundle.st_h
      io.out.info.isHasImm             := false.B
      io.out.info.loadStoreImm         := immSext.asUInt
      io.out.info.gprReadPorts(1).en   := true.B
      io.out.info.gprReadPorts(1).addr := rd
      io.out.info.gprWritePort.en      := false.B
      io.out.info.gprWritePort.addr    := DontCare

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.st_w) {
      io.out.info.isIssueMainPipeline  := true.B
      io.out.isMatched                 := true.B
      io.out.info.exeOp                := OpBundle.st_w
      io.out.info.isHasImm             := false.B
      io.out.info.loadStoreImm         := immSext.asUInt
      io.out.info.gprReadPorts(1).en   := true.B
      io.out.info.gprReadPorts(1).addr := rd
      io.out.info.gprWritePort.en      := false.B
      io.out.info.gprWritePort.addr    := DontCare

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.cacop) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      io.out.info.exeOp               := OpBundle.cacop
      io.out.info.gprWritePort.en     := false.B
      io.out.info.isHasImm            := true.B
      io.out.info.imm                 := immSext.asUInt
      io.out.info.code                := rd
      io.out.info.isPrivilege         := rd(4, 1) =/= "b0100".U // (rd =/= 8.U) && (rd =/= 9.U)
      io.out.info.needRefetch         := true.B

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.preld) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      io.out.info.exeOp               := OpBundle.preld
      io.out.info.isHasImm            := true.B
      io.out.info.imm                 := rd // hint
      io.out.info.loadStoreImm        := immSext.asUInt
      io.out.info.gprWritePort.en     := false.B
      io.out.info.gprWritePort.addr   := DontCare
    }
  }
}
