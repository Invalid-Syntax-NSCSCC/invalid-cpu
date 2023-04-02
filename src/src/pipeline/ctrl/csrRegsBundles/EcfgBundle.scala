package pipeline.ctrl.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class EcfgBundle extends Bundle {
  val zero = UInt(19.W)
  val lie  = UInt(13.W)
}

object EcfgBundle {
  val default = 0.U.asTypeOf(new EcfgBundle)
}
