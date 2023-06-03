package memory.bundles

import chisel3._
import chisel3.util._
import spec._

class CacheMaintenanceNdPort extends Bundle {
  val isL1Valid         = Bool() // Maintenance operation on L1 data/instruction cache
  val isL2Valid         = Bool() // Maintenance operation on L2 cache
  val isInit            = Bool() // Maintenance for initialize cache
  val isCoherentByIndex = Bool() // Maintenance for keeping coherent by index
  val isCoherentByHit   = Bool() // Maintenance for keeping coherent only when hit
  val addr              = UInt(Width.Mem.addr) // Maintenance physical address
}
