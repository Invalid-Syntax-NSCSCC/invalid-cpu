package memory.bundles

import chisel3._
import pipeline.mem.bundles.MemRequestNdPort

class MemRequestHandshakePort extends Bundle {
  val client  = Input(new MemRequestNdPort)
  val isReady = Output(Bool())
}
