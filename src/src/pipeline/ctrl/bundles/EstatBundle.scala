package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class EstatBundle extends Bundle {
  val ecode    = UInt(CsrRegs.Estat.Width.ecode)
  val esubcode = UInt(CsrRegs.Estat.Width.esubcode)
}
