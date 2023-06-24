package control.csrBundles

import chisel3._

class EuenBundle extends Bundle {
  val zero = UInt(31.W)
  val fpe  = Bool()
}

object EuenBundle {
  def default = 0.U.asTypeOf(new EuenBundle)
}
