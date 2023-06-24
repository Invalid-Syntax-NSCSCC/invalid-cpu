package memory.bundles

import chisel3._

class MemAccessPort extends Bundle {
  val req = new MemRequestHandshakePort
  val res = Output(new MemResponseNdPort)
}
