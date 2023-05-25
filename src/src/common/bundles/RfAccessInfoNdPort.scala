package common.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import spec._

class RfAccessInfoNdPort(addrWidth: internal.firrtl.Width = Width.Reg.addr) extends Bundle {
  val en   = Bool()
  val addr = UInt(addrWidth)
}

object RfAccessInfoNdPort {
  def default = (new RfAccessInfoNdPort).Lit(
    _.en -> false.B,
    _.addr -> 0.U
  )
}
