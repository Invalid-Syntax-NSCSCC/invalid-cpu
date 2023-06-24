package control.csrRegsBundles

import chisel3._
import spec._

class EstatBundle extends Bundle {
  val zero1          = Bool()
  val esubcode       = UInt(Csr.Estat.Width.esubcode)
  val ecode          = UInt(Csr.Estat.Width.ecode)
  val zero2          = UInt(3.W)
  val is_ipInt       = Bool()
  val is_timeInt     = Bool()
  val is_hardwareInt = UInt(9.W)
  val is_softwareInt = UInt(2.W)
}

object EstatBundle {
  def default = 0.U.asTypeOf(new EstatBundle)
}
