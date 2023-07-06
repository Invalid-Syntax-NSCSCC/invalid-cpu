package pipeline.queue.decode

import chisel3._
import chisel3.util._
import pipeline.queue.bundles.DecodeOutNdPort
import spec.Inst.{_2RI12 => Inst}
import spec._

class Decoder_2RI12 extends Decoder {
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
  io.out.info.exeSel         := ExeInst.Sel.none
  io.out.info.exeOp          := ExeInst.Op.nop
  io.out.info.imm            := DontCare
  io.out.isMatched           := false.B
  io.out.info.jumpBranchAddr := DontCare

  switch(opcode) {
    is(Inst.slti) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched   := true.B
      io.out.info.exeOp  := ExeInst.Op.slt
      io.out.info.exeSel := ExeInst.Sel.arithmetic
      io.out.info.imm    := immSext.asUInt
    }
    is(Inst.sltui) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched   := true.B
      io.out.info.exeOp  := ExeInst.Op.sltu
      io.out.info.exeSel := ExeInst.Sel.arithmetic
      io.out.info.imm    := immSext.asUInt
    }
    is(Inst.addi_w) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched   := true.B
      io.out.info.exeOp  := ExeInst.Op.add
      io.out.info.exeSel := ExeInst.Sel.arithmetic
      io.out.info.imm    := immSext.asUInt
    }
    is(Inst.andi) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched   := true.B
      io.out.info.exeOp  := ExeInst.Op.and
      io.out.info.exeSel := ExeInst.Sel.logic
      io.out.info.imm    := immZext
    }
    is(Inst.ori) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched   := true.B
      io.out.info.exeOp  := ExeInst.Op.or
      io.out.info.exeSel := ExeInst.Sel.logic
      io.out.info.imm    := immZext
    }
    is(Inst.xori) {
      selectIssueEn(DispatchType.common)

      io.out.isMatched   := true.B
      io.out.info.exeOp  := ExeInst.Op.xor
      io.out.info.exeSel := ExeInst.Sel.logic
      io.out.info.imm    := immZext
    }
    // LoadStore: read0: rj, read1: store reg src, loadStoreImm: offset
    is(Inst.ld_b) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched         := true.B
      io.out.info.exeOp        := ExeInst.Op.ld_b
      io.out.info.exeSel       := ExeInst.Sel.loadStore
      io.out.info.isHasImm     := false.B
      io.out.info.loadStoreImm := immSext.asUInt
    }
    is(Inst.ld_h) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched         := true.B
      io.out.info.exeOp        := ExeInst.Op.ld_h
      io.out.info.exeSel       := ExeInst.Sel.loadStore
      io.out.info.isHasImm     := false.B
      io.out.info.loadStoreImm := immSext.asUInt
    }
    is(Inst.ld_w) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched         := true.B
      io.out.info.exeOp        := ExeInst.Op.ld_w
      io.out.info.exeSel       := ExeInst.Sel.loadStore
      io.out.info.isHasImm     := false.B
      io.out.info.loadStoreImm := immSext.asUInt
    }
    is(Inst.ld_bu) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched         := true.B
      io.out.info.exeOp        := ExeInst.Op.ld_bu
      io.out.info.exeSel       := ExeInst.Sel.loadStore
      io.out.info.isHasImm     := false.B
      io.out.info.loadStoreImm := immSext.asUInt
    }
    is(Inst.ld_hu) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched         := true.B
      io.out.info.exeOp        := ExeInst.Op.ld_hu
      io.out.info.exeSel       := ExeInst.Sel.loadStore
      io.out.info.isHasImm     := false.B
      io.out.info.loadStoreImm := immSext.asUInt
    }
    is(Inst.st_b) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched                 := true.B
      io.out.info.exeOp                := ExeInst.Op.st_b
      io.out.info.exeSel               := ExeInst.Sel.loadStore
      io.out.info.isHasImm             := false.B
      io.out.info.loadStoreImm         := immSext.asUInt
      io.out.info.gprReadPorts(1).en   := true.B
      io.out.info.gprReadPorts(1).addr := rd
      io.out.info.gprWritePort.en      := false.B
      io.out.info.gprWritePort.addr    := DontCare
    }
    is(Inst.st_h) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched                 := true.B
      io.out.info.exeOp                := ExeInst.Op.st_h
      io.out.info.exeSel               := ExeInst.Sel.loadStore
      io.out.info.isHasImm             := false.B
      io.out.info.loadStoreImm         := immSext.asUInt
      io.out.info.gprReadPorts(1).en   := true.B
      io.out.info.gprReadPorts(1).addr := rd
      io.out.info.gprWritePort.en      := false.B
      io.out.info.gprWritePort.addr    := DontCare
    }
    is(Inst.st_w) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched                 := true.B
      io.out.info.exeOp                := ExeInst.Op.st_w
      io.out.info.exeSel               := ExeInst.Sel.loadStore
      io.out.info.isHasImm             := false.B
      io.out.info.loadStoreImm         := immSext.asUInt
      io.out.info.gprReadPorts(1).en   := true.B
      io.out.info.gprReadPorts(1).addr := rd
      io.out.info.gprWritePort.en      := false.B
      io.out.info.gprWritePort.addr    := DontCare
    }
    is(Inst.cacop) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched            := true.B
      io.out.info.exeOp           := ExeInst.Op.cacop
      io.out.info.exeSel          := ExeInst.Sel.loadStore
      io.out.info.gprWritePort.en := false.B
      io.out.info.isHasImm        := true.B
      io.out.info.imm             := immSext.asUInt
      io.out.info.code            := rd
      io.out.info.isPrivilege     := rd(4, 1) =/= "b0100".U // (rd =/= 8.U) && (rd =/= 9.U)
    }
    is(Inst.preld) {
      selectIssueEn(DispatchType.loadStore)

      io.out.isMatched              := true.B
      io.out.info.exeOp             := ExeInst.Op.preld
      io.out.info.exeSel            := ExeInst.Sel.loadStore
      io.out.info.isHasImm          := true.B
      io.out.info.imm               := rd // hint
      io.out.info.loadStoreImm      := immSext.asUInt
      io.out.info.gprWritePort.en   := false.B
      io.out.info.gprWritePort.addr := DontCare
    }
  }
}
