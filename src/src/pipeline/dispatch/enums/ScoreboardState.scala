package pipeline.dispatch.enums

import chisel3.ChiselEnum

object ScoreboardState extends ChiselEnum {
  val free, beforeMem, inMem = Value
}
