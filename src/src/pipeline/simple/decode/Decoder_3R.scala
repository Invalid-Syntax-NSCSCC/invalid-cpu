package pipeline.simple.decode

import chisel3._
import chisel3.util._
import pipeline.simple.decode.bundles.DecodeOutNdPort
import spec.Inst.{_3R => Inst}
import spec._
import spec.ExeInst.OpBundle

class Decoder_3R extends BaseDecoder {
  io.out := DecodeOutNdPort.default

  val opcode      = WireDefault(io.instInfoPort.inst(31, 15))
  val rk          = WireDefault(io.instInfoPort.inst(14, 10))
  val rj          = WireDefault(io.instInfoPort.inst(9, 5))
  val rd          = WireDefault(io.instInfoPort.inst(4, 0))
  val ui5         = WireDefault(rk)
  val rdIsNotZero = WireDefault(rd.orR)
  val invtlbOp    = rd

  val outInfo = io.out.info

  outInfo.isHasImm := false.B

  outInfo.gprReadPorts(0).en   := true.B
  outInfo.gprReadPorts(0).addr := rj
  outInfo.gprReadPorts(1).en   := true.B
  outInfo.gprReadPorts(1).addr := rk
  outInfo.gprWritePort.en      := rdIsNotZero // true.B
  outInfo.gprWritePort.addr    := rd

  // Fallback
  io.out.info.exeOp          := OpBundle.nop
  io.out.info.imm            := DontCare
  io.out.isMatched           := false.B
  io.out.info.jumpBranchAddr := DontCare

  switch(opcode) {
    is(Inst.idle) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.gprReadPorts.foreach(_.en := false.B)
      outInfo.gprWritePort.en := false.B
      outInfo.exeOp           := OpBundle.idle
      io.out.info.isPrivilege := true.B
      io.out.info.needRefetch := true.B
    }
    is(Inst.invtlb) {
      io.out.info.isIssueMainPipeline := true.B
      when(invtlbOp <= 6.U) {
        io.out.isMatched          := true.B
        outInfo.exeOp             := OpBundle.invtlb
        outInfo.jumpBranchAddr    := io.instInfoPort.pcAddr + 4.U
        outInfo.isTlb             := true.B
        outInfo.tlbInvalidateInst := rd
        outInfo.gprWritePort.en   := false.B
        io.out.info.needRefetch   := true.B
        io.out.info.isPrivilege   := true.B
      }
    }
    is(Inst.add_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.add
    }
    is(Inst.sub_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.sub
    }
    is(Inst.slt_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.slt
    }
    is(Inst.sltu_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.sltu
    }
    is(Inst.nor_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.nor
    }
    is(Inst.and_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.and
    }
    is(Inst.or_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.or
    }
    is(Inst.xor_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.xor
    }
    is(Inst.sll_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.sll
    }
    is(Inst.srl_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.srl
    }
    is(Inst.sra_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.sra
    }
    is(Inst.mul_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.mul
    }
    is(Inst.mulh_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.mulh
    }
    is(Inst.mulh_wu) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.mulhu
    }
    is(Inst.div_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.div
    }
    is(Inst.div_wu) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.divu
    }
    is(Inst.mod_w) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.mod
    }
    is(Inst.mod_wu) {
      io.out.isMatched := true.B
      outInfo.exeOp    := OpBundle.modu
    }
    is(Inst.slli_w) {
      io.out.isMatched             := true.B
      outInfo.exeOp                := OpBundle.sll
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.isHasImm             := true.B
      outInfo.imm                  := ui5
    }
    is(Inst.srli_w) {
      io.out.isMatched             := true.B
      outInfo.exeOp                := OpBundle.srl
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.isHasImm             := true.B
      outInfo.imm                  := ui5
    }
    is(Inst.srai_w) {
      io.out.isMatched             := true.B
      outInfo.exeOp                := OpBundle.sra
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.isHasImm             := true.B
      outInfo.imm                  := ui5
    }
    is(Inst.break_) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.break_
      outInfo.gprReadPorts(0).en      := false.B
      outInfo.gprReadPorts(0).addr    := DontCare
      outInfo.gprReadPorts(1).en      := false.B
      outInfo.gprReadPorts(1).addr    := DontCare
      outInfo.gprWritePort.en         := false.B
      outInfo.gprWritePort.addr       := DontCare
      outInfo.needRefetch             := true.B
    }
    is(Inst.syscall) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.syscall
      outInfo.gprReadPorts(0).en      := false.B
      outInfo.gprReadPorts(0).addr    := DontCare
      outInfo.gprReadPorts(1).en      := false.B
      outInfo.gprReadPorts(1).addr    := DontCare
      outInfo.gprWritePort.en         := false.B
      outInfo.gprWritePort.addr       := DontCare
      outInfo.needRefetch             := true.B
    }
  }
}
