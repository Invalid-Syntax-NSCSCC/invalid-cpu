package control.csrRegsBundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class AsidBundle extends Bundle {
  val zero1    = UInt(8.W)
  val asidbits = UInt(8.W)
  val zero2    = UInt(6.W)
  val asid     = UInt(10.W)
}

object AsidBundle {
  val default = 0.U.asTypeOf(new AsidBundle)
}
