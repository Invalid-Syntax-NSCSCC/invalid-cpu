package pipeline.complex.dispatch.enums

import chisel3.ChiselEnum

object ScoreboardState extends ChiselEnum {
  val free, beforeExe, afterExe = Value
}
