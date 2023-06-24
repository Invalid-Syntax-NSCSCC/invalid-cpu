package control.csrRegsBundles

import chisel3._
import spec._

class TlbidxBundle extends Bundle {
  val ne    = Bool()
  val zero1 = Bool()
  val ps    = UInt(6.W)
  val zero2 = UInt((24 - Csr.Tlbidx.Width.index).W)
  val index = UInt(Csr.Tlbidx.Width.index.W)
}

object TlbidxBundle {
  def default = 0.U.asTypeOf(new TlbidxBundle)
}
