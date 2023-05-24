package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class CsrSaveBundle extends Bundle {
  val data = UInt(Width.Reg.data)
}

object CsrSaveBundle {
  val default = 0.U.asTypeOf(new CsrSaveBundle)
}
