package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class TlbeloBundle extends Bundle {
  val ppn  = UInt(24.W)
  val zero = Bool()
  val g    = Bool()
  val mat  = UInt(2.W)
  val plv  = UInt(2.W)
  val d    = Bool()
  val v    = Bool()
}

object TlbeloBundle {
  val default = 0.U.asTypeOf(new TlbeloBundle)
}
