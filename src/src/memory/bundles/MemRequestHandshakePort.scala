package memory.bundles

import chisel3._
import pipeline.common.bundles.MemRequestNdPort

class MemRequestHandshakePort extends Bundle {
  val client  = Input(new MemRequestNdPort)
  val isReady = Output(Bool())
}
