package control.csrRegsBundles

import chisel3._
import spec._

class TvalBundle extends Bundle {
  val zero = {
    UInt((32 - Csr.TimeVal.Width.timeVal).W)
  }
  val timeVal = UInt(Csr.TimeVal.Width.timeVal.W)
}

object TvalBundle {
  def default = 0.U.asTypeOf(new TvalBundle)
}
