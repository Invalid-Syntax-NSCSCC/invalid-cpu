package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class EcodeBundle extends Bundle {
  val esubcode = UInt(CsrRegs.Estat.Width.esubcode)
  val ecode    = UInt(CsrRegs.Estat.Width.ecode)
}
