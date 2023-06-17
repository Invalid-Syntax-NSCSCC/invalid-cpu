package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class BadvBundle extends Bundle {
  val vaddr = UInt(Width.Reg.data)
}

object BadvBundle {
  def default = 0.U.asTypeOf(new BadvBundle)
}
