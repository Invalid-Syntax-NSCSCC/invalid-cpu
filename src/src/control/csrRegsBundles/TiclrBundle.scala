package control.csrRegsBundles

import chisel3._

class TiclrBundle extends Bundle {
  val zero = UInt(31.W)
  val clr  = Bool()
}

object TiclrBundle {
  def default = 0.U.asTypeOf(new TiclrBundle)
}
