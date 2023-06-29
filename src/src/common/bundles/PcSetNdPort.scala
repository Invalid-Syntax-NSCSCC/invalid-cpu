package common.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class PcSetNdPort extends Bundle {
  val isTlb  = Bool()
  val en     = Bool()
  val pcAddr = UInt(Width.Reg.data)
}

object PcSetNdPort {
  def default = (new PcSetNdPort).Lit(
    _.en -> false.B,
    _.pcAddr -> zeroWord,
    _.isTlb -> false.B
  )
}
