package pipeline.simple.decode

import chisel3._
import chisel3.util._
import pipeline.simple.decode.bundles.DecodeOutNdPort
import spec.Inst.{_special => Inst}
import spec._
import spec.ExeInst.OpBundle

class Decoder_special extends BaseDecoder {

  io.out := DecodeOutNdPort.default

  val rd          = WireDefault(io.instInfoPort.inst(4, 0))
  val rdIsNotZero = WireDefault(rd.orR)

  val opcode32 = WireDefault(io.instInfoPort.inst)

  val opcode7 = WireDefault(io.instInfoPort.inst(31, 25))
  val imm20   = WireDefault(io.instInfoPort.inst(24, 5))

  val opcode17 = WireDefault(io.instInfoPort.inst(31, 15))
  val hint     = WireDefault(io.instInfoPort.inst(14, 0))

  val outInfo = io.out.info

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
  io.out.info.exeOp          := OpBundle.nop
  io.out.info.imm            := DontCare
  io.out.isMatched           := false.B
  io.out.info.jumpBranchAddr := DontCare

  switch(opcode7) {
    is(Inst.pcaddu12i) {
      // <=> 0 + imm
      io.out.isMatched             := true.B
      outInfo.exeOp                := OpBundle.add
      outInfo.gprReadPorts(0).en   := true.B
      outInfo.gprReadPorts(0).addr := RegIndex.r0
      outInfo.gprWritePort.en      := rdIsNotZero // true.B
      outInfo.isHasImm             := true.B
      immSext                      := (imm20 << 12).asSInt
      outInfo.imm                  := immSext.asUInt + io.instInfoPort.pcAddr
    }
    is(Inst.lu12i_w) {
      // <=> 0 + imm
      io.out.isMatched             := true.B
      outInfo.exeOp                := OpBundle.add
      outInfo.gprReadPorts(0).en   := true.B
      outInfo.gprReadPorts(0).addr := RegIndex.r0
      outInfo.gprWritePort.en      := rdIsNotZero // true.B
      outInfo.isHasImm             := true.B
      immSext                      := (imm20 << 12).asSInt
      outInfo.imm                  := immSext.asUInt
    }

  }

  switch(opcode17) {
    is(Inst.dbar) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.dbar
      outInfo.isHasImm                := true.B
      immZext                         := hint
      outInfo.imm                     := immZext
    }
    is(Inst.ibar) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.ibar
      outInfo.isHasImm                := true.B
      immZext                         := hint
      outInfo.imm                     := immZext
      io.out.info.needRefetch         := true.B
    }
  }

  // TODO: match only 31 : 20
  switch(opcode32) {
    is(Inst.ertn) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.ertn
      io.out.info.isPrivilege         := true.B
      outInfo.needRefetch             := true.B
    }
    is(Inst.tlbsrch) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.tlbsrch
      outInfo.isTlb                   := true.B
      io.out.info.needRefetch         := true.B
      io.out.info.isPrivilege         := true.B
    }
    is(Inst.tlbrd) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.tlbrd
      outInfo.isTlb                   := true.B
      io.out.info.needRefetch         := true.B
      io.out.info.isPrivilege         := true.B
    }
    is(Inst.tlbwr) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.tlbwr
      outInfo.isTlb                   := true.B
      io.out.info.needRefetch         := true.B
      io.out.info.isPrivilege         := true.B
    }
    is(Inst.tlbfill) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.tlbfill
      outInfo.isTlb                   := true.B
      io.out.info.needRefetch         := true.B
      io.out.info.isPrivilege         := true.B
    }
  }
}
