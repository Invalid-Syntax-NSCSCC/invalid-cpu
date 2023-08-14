package pipeline.simple.decode

import chisel3._
import chisel3.util._
import pipeline.simple.decode.bundles.DecodeOutNdPort
import spec.ExeInst.OpBundle
import spec.Inst.{_2RI14 => Inst}
import spec._

class Decoder_2RI14 extends BaseDecoder {

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
  io.out.info.exeOp          := OpBundle.nop
  io.out.info.imm            := DontCare
  io.out.info.jumpBranchAddr := DontCare

  switch(opcode) {
    is(Inst.ll) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.ll
      outInfo.gprReadPorts(0).en      := true.B
      outInfo.gprReadPorts(0).addr    := rj
      outInfo.gprWritePort.en         := rdIsNotZero // true.B
      outInfo.gprWritePort.addr       := rd
      outInfo.loadStoreImm            := immSext.asUInt << 2
      outInfo.needRefetch             := true.B

      io.out.info.forbidOutOfOrder := true.B
    }
    is(Inst.sc) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.exeOp                   := OpBundle.sc
      outInfo.gprReadPorts(0).en      := true.B
      outInfo.gprReadPorts(0).addr    := rj
      outInfo.gprReadPorts(1).en      := true.B
      outInfo.gprReadPorts(1).addr    := rd
      outInfo.gprWritePort.en         := true.B
      outInfo.gprWritePort.addr       := rd
      outInfo.loadStoreImm            := immSext.asUInt << 2
      outInfo.needRefetch             := true.B

      io.out.info.forbidOutOfOrder := true.B
    }
    // csr读写指令
    is(Inst.csr_) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      outInfo.csrAddr                 := Mux(csrAddrValid, csrAddr, "h80000000".U) // 若不匹配，最高位置1
      outInfo.csrReadEn               := true.B
      io.out.info.isPrivilege         := true.B

      io.out.info.forbidOutOfOrder := true.B
      when(rj === "b00000".U) { // csrrd csr -> rd
        outInfo.exeOp             := OpBundle.csrrd
        outInfo.gprWritePort.en   := rdIsNotZero // true.B
        outInfo.gprWritePort.addr := rd
      }.elsewhen(rj === "b00001".U) {
        outInfo.exeOp                := OpBundle.csrwr
        outInfo.gprReadPorts(0).en   := true.B
        outInfo.gprReadPorts(0).addr := rd
        outInfo.gprWritePort.en      := rdIsNotZero
        outInfo.gprWritePort.addr    := rd
        outInfo.csrWriteEn           := csrAddrValid
        outInfo.needRefetch          := true.B
      }.otherwise {
        outInfo.exeOp                := OpBundle.csrxchg
        outInfo.gprReadPorts(0).en   := true.B
        outInfo.gprReadPorts(0).addr := rd
        outInfo.gprReadPorts(1).en   := true.B
        outInfo.gprReadPorts(1).addr := rj
        outInfo.gprWritePort.en      := rdIsNotZero
        outInfo.gprWritePort.addr    := rd
        outInfo.csrWriteEn           := csrAddrValid
        outInfo.needRefetch          := true.B
      }
    }
  }
}
