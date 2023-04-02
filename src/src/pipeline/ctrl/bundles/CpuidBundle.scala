package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class CpuidBundle extends Bundle {
  val zero   = UInt(23.W)
  val coreId = UInt(9.W)
}

object CpuidBundle {
  val default = 0.U.asTypeOf(new CpuidBundle)
}