package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class LLBitSetBundle extends Bundle {
  val en       = Bool()
  val setValue = Bool()
}

object LLBitSetBundle {
  def default = 0.U.asTypeOf(new LLBitSetBundle)
}
