package memory.bundles

import chisel3._
import chisel3.util._
import spec._

class TlbCompareEntryBundle extends Bundle {
  val isExisted   = Bool()
  val asId        = UInt(Width.Tlb.asid)
  val isGlobal    = Bool()
  val pageSize    = UInt(Width.Tlb.ps)
  val virtPageNum = UInt(Width.Tlb.vppn)
}
