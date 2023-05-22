package control.bundles

import chisel3._
import spec._

class CsrReadPort extends Bundle {
  val en   = Input(Bool())
  val addr = Input(UInt(Width.Csr.addr))
  val data = Output(UInt(Width.Csr.data))
}
