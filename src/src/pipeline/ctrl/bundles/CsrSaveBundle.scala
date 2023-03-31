package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class CsrSaveBundle extends Bundle {
  val data = UInt(Width.Reg.data)
}

object CsrSaveBundle {
  val default = 0.U.asTypeOf(new CsrSaveBundle)
}
