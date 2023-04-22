package pipeline.mem.enums

import chisel3.ChiselEnum

object MemResStageState extends ChiselEnum {
  val free, busy = Value
}
