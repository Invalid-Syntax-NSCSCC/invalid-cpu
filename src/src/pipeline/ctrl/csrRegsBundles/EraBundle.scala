package pipeline.ctrl.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class EraBundle extends Bundle {
  val pc = UInt(Width.Reg.data)
}

object EraBundle {
  val default = 0.U.asTypeOf(new EraBundle)
}
