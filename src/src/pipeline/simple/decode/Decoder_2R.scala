package pipeline.simple.decode

import chisel3._
import chisel3.util._
import pipeline.simple.decode.bundles.DecodeOutNdPort
import spec.Inst.{_2R => Inst}
import spec._
import spec.ExeInst.OpBundle

class Decoder_2R extends BaseDecoder {
  io.out := DecodeOutNdPort.default

  val opcode      = WireDefault(io.instInfoPort.inst(31, 10))
  val rj          = WireDefault(io.instInfoPort.inst(9, 5))
  val rd          = WireDefault(io.instInfoPort.inst(4, 0))
  val rdIsNotZero = WireDefault(rd.orR)

  io.out.info.isHasImm := false.B

  switch(opcode) {
    is(Inst.rdcnt_id_vl) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      when(rj.orR) {
        io.out.info.csrReadEn         := true.B
        io.out.info.csrAddr           := Csr.Index.tid
        io.out.info.exeOp             := OpBundle.csrrd
        io.out.info.gprWritePort.en   := true.B
        io.out.info.gprWritePort.addr := rj
      }.otherwise {
        io.out.info.gprWritePort.addr := rd
        io.out.info.gprWritePort.en   := rdIsNotZero
        io.out.info.exeOp             := OpBundle.rdcntvl_w
      }
    }
    is(Inst.rdcnt_vh) {
      io.out.info.isIssueMainPipeline := true.B
      io.out.isMatched                := true.B
      io.out.info.gprWritePort.en     := rdIsNotZero
      io.out.info.gprWritePort.addr   := rd
      io.out.info.exeOp               := OpBundle.rdcntvh_w
    }
  }
}
