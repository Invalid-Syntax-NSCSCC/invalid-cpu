package memory.bundles

import chisel3._
import spec._

class CacheMaintenanceNdPort extends Bundle {
  val control = new CacheMaintenanceControlNdPort
  val addr    = UInt(Width.Mem.addr) // Maintenance physical address
}
