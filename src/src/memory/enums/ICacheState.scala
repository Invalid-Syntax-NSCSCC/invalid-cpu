package memory.enums

import chisel3.ChiselEnum

object ICacheState extends ChiselEnum {
  val ready, refillForRead, maintenanceInit, maintenanceAll, maintenanceHit = Value
}
