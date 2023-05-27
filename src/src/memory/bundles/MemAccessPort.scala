package memory.bundles

import chisel3._
import common.enums.ReadWriteSel
import pipeline.mem.bundles.MemRequestNdPort
import spec._

class MemAccessPort extends Bundle {
  val req = new MemRequestHandshakePort
  val res = Output(new MemResponseNdPort)
}
