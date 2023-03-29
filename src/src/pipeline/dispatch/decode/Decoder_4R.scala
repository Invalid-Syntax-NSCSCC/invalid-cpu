package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import spec._
import spec.Inst.{_4R => Inst}
import pipeline.dispatch.bundles.DecodeOutNdPort

class Decoder_4R extends Decoder {
  
  io.out := DecodeOutNdPort.default
  
  val opcode = WireDefault(io.instInfoPort.inst(31, 20))
  val ra     = WireDefault(io.instInfoPort.inst(19, 15))
  val rk     = WireDefault(io.instInfoPort.inst(14, 10))
  val rj     = WireDefault(io.instInfoPort.inst(9, 5))
  val rd     = WireDefault(io.instInfoPort.inst(4, 0))

  io.out.info.isHasImm := false.B
}
