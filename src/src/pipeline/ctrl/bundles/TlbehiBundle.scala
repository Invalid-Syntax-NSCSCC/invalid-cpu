package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class TlbehiBundle extends Bundle {
  val vppn = UInt(19.W)
  val zero = UInt(13.W)
}

object TlbehiBundle {
  val default = 0.U.asTypeOf(new TlbehiBundle)
}
