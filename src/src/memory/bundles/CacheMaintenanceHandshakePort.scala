package memory.bundles

import chisel3._

class CacheMaintenanceHandshakePort extends Bundle {
  val client  = Input(new CacheMaintenanceNdPort)
  val isReady = Output(Bool())
}
