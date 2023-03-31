package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class TlbrentryBundle extends Bundle {
  val pa   = UInt(26.W)
  val zero = UInt(6.W)
}

object TlbrentryBundle {
  val default = 0.U.asTypeOf(new TlbrentryBundle)
}
