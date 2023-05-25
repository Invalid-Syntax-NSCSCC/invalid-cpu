package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class EentryBundle extends Bundle {
  val va   = UInt(26.W)
  val zero = UInt(6.W)
}

object EentryBundle {
  val default = 0.U.asTypeOf(new EentryBundle)
}
