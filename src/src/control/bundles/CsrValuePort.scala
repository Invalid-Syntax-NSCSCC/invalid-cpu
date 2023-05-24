package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class CsrValuePort extends Bundle {
  val eentry    = UInt(Width.Reg.data)
  val era       = UInt(Width.Reg.data)
  val tlbrentry = UInt(Width.Reg.data)
}

object CsrValuePort {
  val default = 0.U.asTypeOf(new CsrValuePort)
}
