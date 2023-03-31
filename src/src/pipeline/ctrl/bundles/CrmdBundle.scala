package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class CrmdBundle extends Bundle {
  val zero = UInt(23.W)
  val datm = UInt(2.W)
  val datf = UInt(2.W)
  val pg   = Bool()
  val da   = Bool()
  val ie   = Bool()
  val plv  = UInt(2.W)
}

object CrmdBundle {
  val default = 0.U.asTypeOf(new CrmdBundle)
}
