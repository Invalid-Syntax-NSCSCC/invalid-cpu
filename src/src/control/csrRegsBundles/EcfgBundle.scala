package control.csrBundles

import chisel3._

class EcfgBundle extends Bundle {
  val zero = UInt(19.W)
  val lie  = UInt(13.W)
}

object EcfgBundle {
  def default = 0.U.asTypeOf(new EcfgBundle)
}
