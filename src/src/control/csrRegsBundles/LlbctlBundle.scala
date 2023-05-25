package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class LlbctlBundle extends Bundle {
  val zero  = UInt(29.W)
  val klo   = Bool()
  val wcllb = Bool()
  val rollb = Bool()
}

object LlbctlBundle {
  val default = 0.U.asTypeOf(new LlbctlBundle)
}
