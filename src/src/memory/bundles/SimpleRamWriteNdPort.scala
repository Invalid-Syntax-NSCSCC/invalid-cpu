package memory.bundles

import chisel3._

class SimpleRamWriteNdPort(addrWidth: Int, dataWidth: Int) extends Bundle {
  val en   = Bool()
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
}
