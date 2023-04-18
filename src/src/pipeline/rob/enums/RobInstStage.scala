package pipeline.rob.enums

import chisel3.ChiselEnum

object RobInstStage extends ChiselEnum {
  val empty, busy, ready = Value
}
