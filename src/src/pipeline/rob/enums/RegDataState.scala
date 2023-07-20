package pipeline.rob.enums

import chisel3.ChiselEnum

object RegDataState extends ChiselEnum {
  val ready, busy = Value
}
