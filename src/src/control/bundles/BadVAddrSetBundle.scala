package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class BadVAddrSetBundle extends Bundle {
  val en   = Bool()
  val addr = UInt(Width.Reg.data)
}

object BadVAddrSetBundle {
  def default = 0.U.asTypeOf(new BadVAddrSetBundle)
}
