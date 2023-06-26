package memory.bundles

import chisel3._
import chisel3.util._
import control.csrBundles.{AsidBundle, TlbehiBundle, TlbeloBundle, TlbidxBundle}

class TlbCsrWriteNdPort extends Bundle {
  val tlbidx    = Valid(new TlbidxBundle)
  val tlbehi    = Valid(new TlbehiBundle)
  val tlbeloVec = Vec(2, Valid(new TlbeloBundle))
  val asId      = Valid(new AsidBundle)
}

object TlbCsrWriteNdPort {
  def default = 0.U.asTypeOf(new TlbCsrWriteNdPort)
}
