package pipeline.mem.bundles

import chisel3._
import chisel3.util._
import spec._

class SimpleRamWriteNdPort(addrWidth: Int, dataWidth: Int) extends Bundle {
  val en   = Bool()
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
}
