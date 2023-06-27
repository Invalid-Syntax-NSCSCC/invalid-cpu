package pipeline.memory.bundles

import chisel3._
import chisel3.util._
import spec._
import pipeline.memory.enums.CacheMaintenanceTargetType
import memory.bundles.CacheMaintenanceControlNdPort

class CacheMaintenanceInstNdPort extends Bundle {
  val target  = CacheMaintenanceTargetType()
  val control = new CacheMaintenanceControlNdPort
}
