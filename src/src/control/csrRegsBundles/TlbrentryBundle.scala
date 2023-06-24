package control.csrRegsBundles

import chisel3._

class TlbrentryBundle extends Bundle {
  val pa   = UInt(26.W)
  val zero = UInt(6.W)
}

object TlbrentryBundle {
  def default = 0.U.asTypeOf(new TlbrentryBundle)
}
