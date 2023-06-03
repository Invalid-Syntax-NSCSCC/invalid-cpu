package memory.enums

import chisel3.ChiselEnum

object DCacheState extends ChiselEnum {
  val ready, refillForRead, refillForWrite, onlyWb, maintenanceInit, maintenanceHit, maintenanceOne, maintenanceAll =
    Value
}
