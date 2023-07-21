package control.csrBundles

import chisel3._

class EcfgBundle extends Bundle {
  val zero1 = UInt(19.W)
  val lie2  = UInt(2.W)
  val zero2 = UInt(1.W)
  val lie1  = UInt(10.W)
}

object EcfgBundle {
  def default = 0.U.asTypeOf(new EcfgBundle)
}
