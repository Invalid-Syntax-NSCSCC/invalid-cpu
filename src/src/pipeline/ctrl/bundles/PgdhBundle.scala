package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class PgdhBundle extends Bundle {
  val base = UInt(20.W)
  val zero = UInt(12.W)
}

object PgdhBundle {
  val default = 0.U.asTypeOf(new PgdhBundle)
}
