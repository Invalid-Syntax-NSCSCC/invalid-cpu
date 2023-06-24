package control.csrBundles

import chisel3._
import spec._

class CsrSaveBundle extends Bundle {
  val data = UInt(Width.Reg.data)
}

object CsrSaveBundle {
  def default = 0.U.asTypeOf(new CsrSaveBundle)
}
