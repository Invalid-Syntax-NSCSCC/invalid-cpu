package memory.bundles

import chisel3._
import chisel3.util._
import pipeline.mem.bundles.MemRequestNdPort
import spec._

class MemRequestHandshakePort extends Bundle {
  val client  = Input(new MemRequestNdPort)
  val isReady = Output(Bool())
}
