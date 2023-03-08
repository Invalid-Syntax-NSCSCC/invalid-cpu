package common.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import spec._

class RfAccessInfoNdPort extends Bundle {
  val en   = Bool()
  val addr = UInt(Width.Reg.addr)
}

object RfAccessInfoNdPort {
  def default = (new RfAccessInfoNdPort).Lit(
    _.en -> false.B,
    _.addr -> 0.U
  )
}
