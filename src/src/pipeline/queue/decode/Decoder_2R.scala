package pipeline.queue.decode

import chisel3._
import chisel3.util._
import pipeline.queue.bundles.DecodeOutNdPort
import spec._
import spec.Inst.{_2R => Inst}

class Decoder_2R extends Decoder {
  io.out := DecodeOutNdPort.default

  val opcode      = WireDefault(io.instInfoPort.inst(31, 10))
  val rj          = WireDefault(io.instInfoPort.inst(9, 5))
  val rd          = WireDefault(io.instInfoPort.inst(4, 0))
  val rdIsNotZero = WireDefault(rd.orR)

  io.out.info.isHasImm := false.B

  switch(opcode) {
    is(Inst.rdcnt_id_vl) {
      io.out.isMatched := true.B

      when(rj.orR) {
        io.out.info.needCsr           := true.B
        io.out.info.csrReadEn         := true.B
        io.out.info.csrAddr           := Csr.Index.tid
        io.out.info.exeOp             := ExeInst.Op.csrrd
        io.out.info.gprWritePort.en   := true.B
        io.out.info.gprWritePort.addr := rj
      }.otherwise {
        io.out.info.gprWritePort.addr := rd
        io.out.info.gprWritePort.en   := rdIsNotZero
        io.out.info.exeOp             := ExeInst.Op.rdcntvl_w
      }
    }
    is(Inst.rdcnt_vh) {
      io.out.isMatched              := true.B
      io.out.info.gprWritePort.en   := rdIsNotZero
      io.out.info.gprWritePort.addr := rd
      io.out.info.exeOp             := ExeInst.Op.rdcntvh_w
    }
  }
}
