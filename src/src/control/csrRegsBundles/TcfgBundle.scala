package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class TcfgBundle extends Bundle {
  val zero     = UInt((32 - Csr.TimeVal.Width.timeVal).W)
  val initVal  = UInt((Csr.TimeVal.Width.timeVal - 2).W)
  val periodic = Bool()
  val en       = Bool()
}

object TcfgBundle {
  val default = 0.U.asTypeOf(new TcfgBundle)
}
