package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class CuToCsrNdPort extends Bundle {
  val exceptionFlush = Bool()
  val era = UInt(Width.Reg.data)
  val ecodeBunle = new EcodeBundle
}

object CuToCsrNdPort {
  val default = 0.U.asTypeOf(new CuToCsrNdPort)
}
