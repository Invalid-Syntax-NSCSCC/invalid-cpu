package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class PrmdBundle extends Bundle {
  val zero = UInt(29.W)
  val pie  = Bool()
  val pplv = UInt(2.W)
}

object PrmdBundle {
  def default = 0.U.asTypeOf(new PrmdBundle)
}
