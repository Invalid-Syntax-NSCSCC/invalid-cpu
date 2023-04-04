package pipeline.mem.bundles

import chisel3._
import chisel3.util._
import spec._

class SimpleRamReadPort(addrWidth: Int, dataWidth: Int) extends Bundle {
  val addr = Input(UInt(addrWidth.W))
  val data = Output(UInt(dataWidth.W))
}
