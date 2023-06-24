package control.csrRegsBundles

import chisel3._
import spec._

class TidBundle extends Bundle {
  val tid = UInt(Width.Reg.data)
}

object TidBundle {
  def default = 0.U.asTypeOf(new TidBundle)
}
