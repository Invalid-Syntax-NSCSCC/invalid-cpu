package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class TlbehiBundle extends Bundle {
  val vppn = UInt(19.W)
  val zero = UInt(13.W)
}

object TlbehiBundle {
  def default = 0.U.asTypeOf(new TlbehiBundle)
}
