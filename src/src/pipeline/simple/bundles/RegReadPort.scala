package pipeline.simple.bundles

import chisel3._
import chisel3.util._
import spec._

class RegReadPort extends Bundle {
  val addr = Input(UInt(Width.Reg.addr))
  val data = Output(Valid(UInt(Width.Reg.data)))
}
