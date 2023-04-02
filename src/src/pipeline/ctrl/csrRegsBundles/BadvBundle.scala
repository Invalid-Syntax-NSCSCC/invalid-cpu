package pipeline.ctrl.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class BadvBundle extends Bundle {
  val vaddr = UInt(Width.Reg.data)
}

object BadvBundle {
  val default = 0.U.asTypeOf(new BadvBundle)
}
