package common.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class RfWriteNdPort extends Bundle {
  val en   = Bool()
  val addr = UInt(Width.Reg.addr)
  val data = UInt(Width.Reg.data)
}

object RfWriteNdPort {
  def default = (new RfWriteNdPort).Lit(
    _.en -> false.B,
    _.addr -> 0.U,
    _.data -> 0.U
  )
}
