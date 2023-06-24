package control.csrRegsBundles

import chisel3._

class PrmdBundle extends Bundle {
  val zero = UInt(29.W)
  val pie  = Bool()
  val pplv = UInt(2.W)
}

object PrmdBundle {
  def default = 0.U.asTypeOf(new PrmdBundle)
}
