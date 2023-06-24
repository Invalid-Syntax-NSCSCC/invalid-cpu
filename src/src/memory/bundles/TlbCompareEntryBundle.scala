package memory.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class TlbCompareEntryBundle extends Bundle {
  val isExisted   = Bool()
  val asId        = UInt(Width.Tlb.asid)
  val isGlobal    = Bool()
  val pageSize    = UInt(Width.Tlb.ps)
  val virtPageNum = UInt(Width.Tlb.vppn)
}

object TlbCompareEntryBundle {
  def default = (new TlbCompareEntryBundle).Lit(
    _.isExisted -> false.B,
    _.asId -> 0.U,
    _.isGlobal -> false.B,
    _.pageSize -> 0.U,
    _.virtPageNum -> 0.U
  )
}
