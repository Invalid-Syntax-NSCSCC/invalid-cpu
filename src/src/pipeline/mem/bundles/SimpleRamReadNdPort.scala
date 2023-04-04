package pipeline.mem.bundles

import chisel3._
import chisel3.util._
import spec._

class SimpleRamReadNdPort(addrWidth: Int, dataWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
}
