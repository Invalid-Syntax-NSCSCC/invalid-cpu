package memory.enums

import chisel3.ChiselEnum

object UncachedAgentState extends ChiselEnum {
  val ready, waitReady, waitRes = Value
}
