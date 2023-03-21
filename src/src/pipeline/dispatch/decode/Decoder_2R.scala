package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import spec._
import spec.Inst.{_2R => Inst}

class Decoder_2R extends Decoder {
  io.out := DontCare

  val opcode = WireDefault(io.instInfoPort.inst(31, 10))
  val rj     = WireDefault(io.instInfoPort.inst(9, 5))
  val rd     = WireDefault(io.instInfoPort.inst(4, 0))

  io.out.info.isHasImm := false.B
}
