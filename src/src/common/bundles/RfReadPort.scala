package common.bundles

import chisel3._
import spec._

class RfReadPort extends Bundle {
  val en   = Input(Bool())
  val addr = Input(UInt(Width.Reg.addr))
  val data = Output(UInt(Width.Reg.data))
}

