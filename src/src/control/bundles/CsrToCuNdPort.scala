package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class CsrToCuNdPort extends Bundle {
  val eentry    = UInt(Width.Reg.data)
  val era       = UInt(Width.Reg.data)
  val tlbrentry = UInt(Width.Reg.data)
}

object CsrToCuNdPort {
  val default = 0.U.asTypeOf(new CsrToCuNdPort)
}
