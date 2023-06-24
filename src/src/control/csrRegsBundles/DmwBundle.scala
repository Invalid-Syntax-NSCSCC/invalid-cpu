package control.csrRegsBundles

import chisel3._

class DmwBundle extends Bundle {
  val vseg  = UInt(3.W)
  val zero1 = Bool()
  val pseg  = UInt(3.W)
  val zero2 = UInt(19.W)
  val mat   = UInt(2.W)
  val plv3  = Bool()
  val zero3 = UInt(2.W)
  val plv0  = Bool()
}

object DmwBundle {
  def default = 0.U.asTypeOf(new DmwBundle)
}
