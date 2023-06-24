package control.csrRegsBundles

import chisel3._

class EentryBundle extends Bundle {
  val va   = UInt(26.W)
  val zero = UInt(6.W)
}

object EentryBundle {
  def default = 0.U.asTypeOf(new EentryBundle)
}
