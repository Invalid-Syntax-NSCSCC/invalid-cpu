package memory.bundles

import chisel3._
import chisel3.util._
import spec._

class CacheMaintenanceHandshakePort extends Bundle {
  val client  = Input(new CacheMaintenanceNdPort)
  val isReady = Output(Bool())
}
