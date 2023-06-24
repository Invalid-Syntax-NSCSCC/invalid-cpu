package control.csrBundles

import chisel3._

class LlbctlBundle extends Bundle {
  val zero  = UInt(29.W)
  val klo   = Bool()
  val wcllb = Bool()
  val rollb = Bool()
}

object LlbctlBundle {
  def default = 0.U.asTypeOf(new LlbctlBundle)
}
