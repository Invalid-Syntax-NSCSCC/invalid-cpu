package pipeline.simple.decode

import chisel3._
import chisel3.util._
import pipeline.simple.decode.bundles.DecodeOutNdPort
import spec.Inst.{_2RI16 => Inst}
import spec.Param.BPU.BranchType
import spec._

class Decoder_2RI16 extends BaseDecoder {
  io.out := DecodeOutNdPort.default

  val opcode      = WireDefault(io.instInfoPort.inst(31, 26))
  val imm16       = WireDefault(io.instInfoPort.inst(25, 10))
  val rj          = WireDefault(io.instInfoPort.inst(9, 5))
  val rd          = WireDefault(io.instInfoPort.inst(4, 0))
  val imm26       = WireDefault(Cat(rj, rd, imm16))
  val rdIsNotZero = WireDefault(rd.orR)

  val outInfo = io.out.info

  // It has immediate
  io.out.info.isHasImm := false.B

  // Extend immediate
  val imm16SextShift2 = Wire(SInt(Width.Reg.data))
  val imm16ZextShift2 = Wire(UInt(Width.Reg.data))
  val imm26SextShift2 = Wire(SInt(Width.Reg.data))
  val imm26ZextShift2 = Wire(UInt(Width.Reg.data))
  imm16SextShift2 := (imm16 << 2).asSInt
  imm16ZextShift2 := imm16 << 2
  imm26SextShift2 := (imm26 << 2).asSInt
  imm26ZextShift2 := imm26 << 2

  // Read and write GPR
  io.out.info.gprReadPorts(0).en   := true.B
  io.out.info.gprReadPorts(0).addr := rj
  io.out.info.gprReadPorts(1).en   := true.B
  io.out.info.gprReadPorts(1).addr := rd
  io.out.info.gprWritePort.en      := false.B
  io.out.info.gprWritePort.addr    := DontCare

  // Fallback
  io.out.info.exeSel         := ExeInst.Sel.none
  io.out.info.exeOp          := ExeInst.Op.nop
  io.out.info.imm            := DontCare
  io.out.isMatched           := false.B
  io.out.info.jumpBranchAddr := DontCare

  switch(opcode) {
    is(Inst.b_) {
      io.out.info.forbidOutOfOrder := true.B
      outInfo.gprReadPorts(0).en   := false.B
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(0).addr := DontCare
      outInfo.gprReadPorts(1).addr := DontCare
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.b
      outInfo.exeSel               := ExeInst.Sel.jumpBranch
      outInfo.isBranch             := true.B
      outInfo.branchType           := BranchType.uncond
      outInfo.jumpBranchAddr       := imm26SextShift2.asUInt + io.instInfoPort.pcAddr
    }
    is(Inst.bl) {
      io.out.info.forbidOutOfOrder := true.B
      outInfo.gprReadPorts(0).en   := false.B
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(0).addr := DontCare
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.gprWritePort.en      := true.B
      outInfo.gprWritePort.addr    := RegIndex.r1
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.bl
      outInfo.exeSel               := ExeInst.Sel.jumpBranch
      outInfo.isBranch             := true.B
      outInfo.branchType           := BranchType.call
      outInfo.jumpBranchAddr       := imm26SextShift2.asUInt + io.instInfoPort.pcAddr
    }
    is(Inst.jirl) {
      io.out.info.forbidOutOfOrder := true.B
      outInfo.gprReadPorts(1).en   := false.B
      outInfo.gprReadPorts(1).addr := DontCare
      outInfo.gprWritePort.en      := rdIsNotZero // true.B
      outInfo.gprWritePort.addr    := rd
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.jirl
      outInfo.exeSel               := ExeInst.Sel.jumpBranch
      outInfo.isBranch             := true.B
      outInfo.branchType := Mux(
        rd === 0.U && rj === 1.U,
        BranchType.ret,
        Mux(
          rd === 1.U,
          BranchType.call,
          BranchType.uncond
        )
      )
      outInfo.jumpBranchAddr := imm16SextShift2.asUInt
    }
    is(Inst.beq) {
      io.out.info.forbidOutOfOrder := true.B
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.beq
      outInfo.exeSel               := ExeInst.Sel.jumpBranch
      outInfo.isBranch             := true.B
      outInfo.branchType           := BranchType.cond
      outInfo.jumpBranchAddr       := imm16SextShift2.asUInt + io.instInfoPort.pcAddr
    }
    is(Inst.bne) {
      io.out.info.forbidOutOfOrder := true.B
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.bne
      outInfo.exeSel               := ExeInst.Sel.jumpBranch
      outInfo.isBranch             := true.B
      outInfo.branchType           := BranchType.cond
      outInfo.jumpBranchAddr       := imm16SextShift2.asUInt + io.instInfoPort.pcAddr
    }
    is(Inst.blt) {
      io.out.info.forbidOutOfOrder := true.B
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.blt
      outInfo.exeSel               := ExeInst.Sel.jumpBranch
      outInfo.isBranch             := true.B
      outInfo.branchType           := BranchType.cond
      outInfo.jumpBranchAddr       := imm16SextShift2.asUInt + io.instInfoPort.pcAddr
    }
    is(Inst.bge) {
      io.out.info.forbidOutOfOrder := true.B
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.bge
      outInfo.exeSel               := ExeInst.Sel.jumpBranch
      outInfo.isBranch             := true.B
      outInfo.branchType           := BranchType.cond
      outInfo.jumpBranchAddr       := imm16SextShift2.asUInt + io.instInfoPort.pcAddr
    }
    is(Inst.bltu) {
      io.out.info.forbidOutOfOrder := true.B
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.bltu
      outInfo.exeSel               := ExeInst.Sel.jumpBranch
      outInfo.isBranch             := true.B
      outInfo.branchType           := BranchType.cond
      outInfo.jumpBranchAddr       := imm16SextShift2.asUInt + io.instInfoPort.pcAddr
    }
    is(Inst.bgeu) {
      io.out.info.forbidOutOfOrder := true.B
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.bgeu
      outInfo.exeSel               := ExeInst.Sel.jumpBranch
      outInfo.isBranch             := true.B
      outInfo.branchType           := BranchType.cond
      outInfo.jumpBranchAddr       := imm16SextShift2.asUInt + io.instInfoPort.pcAddr
    }
  }
}
