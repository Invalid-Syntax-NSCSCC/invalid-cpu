package control.csrRegsBundles

import chisel3._

class TlbehiBundle extends Bundle {
  val vppn = UInt(19.W)
  val zero = UInt(13.W)
}

object TlbehiBundle {
  def default = 0.U.asTypeOf(new TlbehiBundle)
}
