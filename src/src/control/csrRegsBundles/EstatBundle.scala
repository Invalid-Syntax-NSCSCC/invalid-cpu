package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class EstatBundle extends Bundle {
  val zero1    = Bool()
  val esubcode = UInt(CsrRegs.Estat.Width.esubcode)
  val ecode    = UInt(CsrRegs.Estat.Width.ecode)
  val zero2    = UInt(3.W)
  val is       = UInt(13.W)
}

object EstatBundle {
  val default = 0.U.asTypeOf(new EstatBundle)
}
