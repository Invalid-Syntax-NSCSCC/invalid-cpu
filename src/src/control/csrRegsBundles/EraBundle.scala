package control.csrBundles

import chisel3._
import spec._

class EraBundle extends Bundle {
  val pc = UInt(Width.Reg.data)
}

object EraBundle {
  def default = 0.U.asTypeOf(new EraBundle)
}
