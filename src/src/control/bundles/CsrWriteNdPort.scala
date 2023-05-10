package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class CsrWriteNdPort extends Bundle {
  val en   = Bool()
  val addr = UInt(Width.Csr.addr)
  val data = UInt(Width.Csr.data)
}

object CsrWriteNdPort {
  val default = (new CsrWriteNdPort).Lit(
    _.en -> false.B,
    _.addr -> zeroWord,
    _.data -> zeroWord
  )
}
