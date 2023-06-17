package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class EcfgBundle extends Bundle {
  val zero = UInt(19.W)
  val lie  = UInt(13.W)
}

object EcfgBundle {
  def default = 0.U.asTypeOf(new EcfgBundle)
}
