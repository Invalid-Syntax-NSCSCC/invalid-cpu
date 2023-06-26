package memory.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class TlbTransEntryBundle extends Bundle {
  val isValid     = Bool()
  val isDirty     = Bool()
  val mat         = UInt(2.W)
  val plv         = UInt(2.W)
  val physPageNum = UInt(Width.Tlb.ppn)
}

object TlbTransEntryBundle {
  def default = (new TlbTransEntryBundle).Lit(
    _.isValid -> false.B,
    _.isDirty -> false.B,
    _.mat -> 0.U,
    _.plv -> 0.U,
    _.physPageNum -> 0.U
  )
}
