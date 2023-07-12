package pipeline.memory.bundles

import chisel3._
import memory.bundles.CacheMaintenanceControlNdPort
import pipeline.memory.enums.CacheMaintenanceTargetType

class CacheMaintenanceInstNdPort extends Bundle {
  val target  = CacheMaintenanceTargetType()
  val control = new CacheMaintenanceControlNdPort
}
