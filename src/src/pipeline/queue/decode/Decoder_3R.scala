package pipeline.queue.decode

import chisel3._
import chisel3.util._
import pipeline.queue.bundles.DecodeOutNdPort
import spec.Inst.{_3R => Inst}
import spec._

class Decoder_3R extends Decoder {

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
  io.out.info.exeSel         := ExeInst.Sel.none
  io.out.info.exeOp          := ExeInst.Op.nop
  io.out.info.imm            := DontCare
  io.out.isMatched           := false.B
  io.out.info.jumpBranchAddr := DontCare

  switch(opcode) {
    is(Inst.idle) {
      selectIssueEn(DispatchType.csrOrBranch)
      io.out.info.forbidOutOfOrder := true.B

      io.out.isMatched := true.B
      outInfo.gprReadPorts.foreach(_.en := false.B)
      outInfo.gprWritePort.en := false.B
      outInfo.exeOp           := ExeInst.Op.idle
      outInfo.exeSel          := ExeInst.Sel.jumpBranch
      io.out.info.isPrivilege := true.B
    }
    is(Inst.invtlb) {
      selectIssueEn(DispatchType.loadStore)
      io.out.info.forbidOutOfOrder := true.B

      when(invtlbOp <= 6.U) {
        io.out.isMatched          := true.B
        outInfo.exeOp             := ExeInst.Op.invtlb
        outInfo.exeSel            := ExeInst.Sel.loadStore
        outInfo.jumpBranchAddr    := io.instInfoPort.pcAddr + 4.U
        outInfo.isTlb             := true.B
        outInfo.tlbInvalidateInst := rd
        outInfo.gprWritePort.en   := false.B
        outInfo.needCsr           := true.B
        io.out.info.isPrivilege   := true.B
      }
    }
    is(Inst.add_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.add
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.sub_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.sub
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.slt_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.slt
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.sltu_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.sltu
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.nor_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.nor
      outInfo.exeSel   := ExeInst.Sel.logic
    }
    is(Inst.and_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.and
      outInfo.exeSel   := ExeInst.Sel.logic
    }
    is(Inst.or_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.or
      outInfo.exeSel   := ExeInst.Sel.logic
    }
    is(Inst.xor_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.xor
      outInfo.exeSel   := ExeInst.Sel.logic
    }
    is(Inst.sll_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.sll
      outInfo.exeSel   := ExeInst.Sel.shift
    }
    is(Inst.srl_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.srl
      outInfo.exeSel   := ExeInst.Sel.shift
    }
    is(Inst.sra_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.sra
      outInfo.exeSel   := ExeInst.Sel.shift
    }
    is(Inst.mul_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.mul
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.mulh_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.mulh
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.mulh_wu) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.mulhu
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.div_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.div
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.div_wu) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.divu
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.mod_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.mod
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.mod_wu) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched := true.B
      outInfo.exeOp    := ExeInst.Op.modu
      outInfo.exeSel   := ExeInst.Sel.arithmetic
    }
    is(Inst.slli_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.sll
      outInfo.exeSel               := ExeInst.Sel.shift
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.isHasImm             := true.B
      outInfo.imm                  := ui5
    }
    is(Inst.srli_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.srl
      outInfo.exeSel               := ExeInst.Sel.shift
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.isHasImm             := true.B
      outInfo.imm                  := ui5
    }
    is(Inst.srai_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.sra
      outInfo.exeSel               := ExeInst.Sel.shift
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.isHasImm             := true.B
      outInfo.imm                  := ui5
    }
    is(Inst.break_) {
      selectIssueEn(DispatchType.csrOrBranch)
      io.out.info.forbidOutOfOrder := true.B

      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.break_
      outInfo.needCsr              := true.B
      outInfo.gprReadPorts(0).en   := false.B
      outInfo.gprReadPorts(0).addr := DontCare
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.gprWritePort.en      := false.B
      outInfo.gprWritePort.addr    := DontCare
    }
    is(Inst.syscall) {
      selectIssueEn(DispatchType.csrOrBranch)
      io.out.info.forbidOutOfOrder := true.B

      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.syscall
      outInfo.needCsr              := true.B
      outInfo.gprReadPorts(0).en   := false.B
      outInfo.gprReadPorts(0).addr := DontCare
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.gprWritePort.en      := false.B
      outInfo.gprWritePort.addr    := DontCare
    }
  }
}
