package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import spec._
import spec.Inst.{_2R => Inst}

class Docoder_2R extends Decoder {
  io.out := DontCare

  val opcode = WireDefault(io.inst(31,10))
  val rj = WireDefault(io.inst(9,5))
  val rd = WireDefault(io.inst(4,0))

  io.out.info.isHasImm := false.B
}
