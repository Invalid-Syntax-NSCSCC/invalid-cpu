package common.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class PcSetPort extends Bundle {
  val isTlb  = Bool()
  val en     = Bool()
  val pcAddr = UInt(Width.Reg.data)
}

object PcSetPort {
  def default = (new PcSetPort).Lit(
    _.en -> false.B,
    _.pcAddr -> zeroWord,
    _.isTlb -> false.B
  )
}
