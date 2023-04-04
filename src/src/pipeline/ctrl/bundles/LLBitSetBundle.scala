package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class LLBitSetBundle extends Bundle {
  val en       = Bool()
  val setValue = Bool()
}

object LLBitSetBundle {
  val default = 0.U.asTypeOf(new LLBitSetBundle)
}
