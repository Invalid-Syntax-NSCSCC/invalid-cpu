package common.bundles

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals._

class PcSetPort extends Bundle {
  val isIdle = Bool()
  val en     = Bool()
  val pcAddr = UInt(Width.Reg.data)
}

object PcSetPort {
  val default = (new PcSetPort).Lit(
    _.en -> false.B,
    _.pcAddr -> zeroWord,
    _.isIdle -> false.B
  )
}
