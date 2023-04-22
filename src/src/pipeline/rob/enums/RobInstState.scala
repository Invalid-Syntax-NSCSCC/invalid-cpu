package pipeline.rob.enums

import chisel3.ChiselEnum

object RobInstState extends ChiselEnum {
  val empty, busy, ready = Value
}
