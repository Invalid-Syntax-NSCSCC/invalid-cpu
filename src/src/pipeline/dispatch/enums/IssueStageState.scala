package pipeline.dispatch.enums

import chisel3.ChiselEnum

object IssueStageState extends ChiselEnum {
  val nonBlocking, blocking = Value
}
