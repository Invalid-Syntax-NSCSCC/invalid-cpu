package pipeline.memory.enums

import chisel3._

object CacheMaintenanceType extends ChiselEnum {
  val none, l1I, l1D = Value
}
