package pipeline.queue.decode

import chisel3._
import chisel3.util._
import pipeline.queue.bundles.DecodeOutNdPort
import spec.Inst.{_2RI14 => Inst}
import spec._

class Decoder_2RI14 extends Decoder {

  io.out := DecodeOutNdPort.default

  val opcode      = WireDefault(io.instInfoPort.inst(31, 24))
  val imm14       = WireDefault(io.instInfoPort.inst(23, 10))
  val rj          = WireDefault(io.instInfoPort.inst(9, 5))
  val rd          = WireDefault(io.instInfoPort.inst(4, 0))
  val rdIsNotZero = WireDefault(rd.orR)

  val outInfo = io.out.info

  // It has immediate
  io.out.info.isHasImm := false.B

  // Extend immediate
  val immSext = Wire(SInt(Width.Reg.data))
  val immZext = Wire(UInt(Width.Reg.data))
  immSext := imm14.asSInt
  immZext := imm14

  // Csr Addr -> Index Map
  val csrAddrMap      = WireDefault(VecInit(Csr.addrs))
  val csrMatchIndices = csrAddrMap.map(_ === immZext)
  val csrAddrValid    = csrMatchIndices.reduce(_ || _)
  val csrAddr         = Mux1H(csrMatchIndices, Seq.range(0, csrMatchIndices.length).map(_.U(14.W)))

  // Read and write GPR
  io.out.info.gprReadPorts(0).en   := false.B
  io.out.info.gprReadPorts(0).addr := zeroWord
  io.out.info.gprReadPorts(1).en   := false.B
  io.out.info.gprReadPorts(1).addr := zeroWord
  io.out.info.gprWritePort.en      := false.B
  io.out.info.gprWritePort.addr    := zeroWord

  // Fallback
  io.out.info.exeSel         := ExeInst.Sel.none
  io.out.info.exeOp          := ExeInst.Op.nop
  io.out.info.imm            := DontCare
  io.out.info.jumpBranchAddr := DontCare

  switch(opcode) {
    is(Inst.ll) {
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.ll
      outInfo.exeSel               := ExeInst.Sel.loadStore
      outInfo.gprReadPorts(0).en   := true.B
      outInfo.gprReadPorts(0).addr := rj
      outInfo.gprWritePort.en      := rdIsNotZero // true.B
      outInfo.gprWritePort.addr    := rd
      outInfo.loadStoreImm         := immSext.asUInt << 2
    }
    is(Inst.sc) {
      io.out.isMatched             := true.B
      outInfo.exeOp                := ExeInst.Op.sc
      outInfo.exeSel               := ExeInst.Sel.loadStore
      outInfo.gprReadPorts(0).en   := true.B
      outInfo.gprReadPorts(0).addr := rj
      outInfo.gprReadPorts(1).en   := true.B
      outInfo.gprReadPorts(1).addr := rd
      outInfo.gprWritePort.en      := true.B
      outInfo.gprWritePort.addr    := rd
      outInfo.loadStoreImm         := immSext.asUInt << 2
    }
    // csr读写指令
    is(Inst.csr_) {
      io.out.isMatched  := csrAddrValid
      outInfo.exeSel    := ExeInst.Sel.none
      outInfo.csrAddr   := csrAddr
      outInfo.csrReadEn := true.B
      outInfo.needCsr   := true.B
      when(rj === "b00000".U) { // csrrd csr -> rd
        outInfo.exeOp             := ExeInst.Op.csrrd
        outInfo.gprWritePort.en   := rdIsNotZero // true.B
        outInfo.gprWritePort.addr := rd
      }.elsewhen(rj === "b00001".U) {
        outInfo.exeOp                := ExeInst.Op.csrwr
        outInfo.gprReadPorts(0).en   := true.B
        outInfo.gprReadPorts(0).addr := rd
        outInfo.gprWritePort.en      := rdIsNotZero
        outInfo.gprWritePort.addr    := rd
        outInfo.csrWriteEn           := true.B
      }.otherwise {
        outInfo.exeOp                := ExeInst.Op.csrxchg
        outInfo.gprReadPorts(0).en   := true.B
        outInfo.gprReadPorts(0).addr := rd
        outInfo.gprReadPorts(1).en   := true.B
        outInfo.gprReadPorts(1).addr := rj
        outInfo.gprWritePort.en      := rdIsNotZero
        outInfo.gprWritePort.addr    := rd
        outInfo.csrWriteEn           := true.B
      }
    }
  }
}
