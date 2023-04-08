package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class EuenBundle extends Bundle {
  val zero = UInt(31.W)
  val fpe  = Bool()
}

object EuenBundle {
  val default = 0.U.asTypeOf(new EuenBundle)
}
