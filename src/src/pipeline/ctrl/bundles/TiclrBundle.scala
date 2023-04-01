package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class TiclrBundle extends Bundle {
  val zero = UInt(31.W)
  val clr  = Bool()
}

object TiclrBundle {
  val default = 0.U.asTypeOf(new TiclrBundle)
}
