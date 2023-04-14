package control.bundles

import chisel3._
import spec._

class CsrReadPort extends Bundle {
  val en   = Input(Bool())
  val addr = Input(UInt(Width.CsrReg.addr))
  val data = Output(UInt(Width.CsrReg.data))
}
