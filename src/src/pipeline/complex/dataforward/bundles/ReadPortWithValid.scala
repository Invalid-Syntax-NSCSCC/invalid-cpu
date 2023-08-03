package pipeline.complex.dataforward.bundles

import chisel3._
import spec._

class ReadPortWithValid extends Bundle {
  val en    = Input(Bool())
  val addr  = Input(UInt(Width.Reg.addr))
  val valid = Output(Bool())
  val data  = Output(UInt(Width.Reg.data))
}
