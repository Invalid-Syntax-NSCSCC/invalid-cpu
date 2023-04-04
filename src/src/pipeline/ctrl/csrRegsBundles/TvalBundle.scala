package pipeline.ctrl.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class TvalBundle extends Bundle {
  val zero    = UInt((32 - CsrRegs.TimeVal.Width.timeVal).W)
  val timeVal = UInt((CsrRegs.TimeVal.Width.timeVal).W)
}

object TvalBundle {
  val default = 0.U.asTypeOf(new TvalBundle)
}
