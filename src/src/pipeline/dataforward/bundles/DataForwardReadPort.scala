package pipeline.dataforward.bundles

import chisel3._
import common.bundles.RfReadPort
import spec._

class DataForwardReadPort extends Bundle {
  val en    = Input(Bool())
  val addr  = Input(UInt(Width.Reg.addr))
  val valid = Output(Bool())
  val data  = Output(UInt(Width.Reg.data))
}
