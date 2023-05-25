package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class TvalBundle extends Bundle {
  val zero = {
    UInt((32 - Csr.TimeVal.Width.timeVal).W)
  }
  val timeVal = UInt(Csr.TimeVal.Width.timeVal.W)
}

object TvalBundle {
  val default = 0.U.asTypeOf(new TvalBundle)
}
