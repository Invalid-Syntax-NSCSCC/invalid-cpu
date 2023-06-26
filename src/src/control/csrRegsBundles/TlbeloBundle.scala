package control.csrBundles

import chisel3._

class TlbeloBundle extends Bundle {
  val ppn  = UInt((spec.Csr.Tlbelo.Width.palen - 12).W)
  val zero = Bool()
  val g    = Bool()
  val mat  = UInt(2.W)
  val plv  = UInt(2.W)
  val d    = Bool()
  val v    = Bool()
}

object TlbeloBundle {
  def default = 0.U.asTypeOf(new TlbeloBundle)
}
