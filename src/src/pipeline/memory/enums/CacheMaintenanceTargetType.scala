package pipeline.memory.enums

import chisel3.ChiselEnum

object CacheMaintenanceTargetType extends ChiselEnum {
  val inst, data = Value
}
