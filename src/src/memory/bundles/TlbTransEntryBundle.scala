package memory.bundles

import chisel3._
import chisel3.util._
import spec._

class TlbTransEntryBundle extends Bundle {
  val isValid     = Bool()
  val isDirty     = Bool()
  val mat         = UInt(2.W)
  val plv         = UInt(2.W)
  val physPageNum = UInt(Width.Tlb.ppn)
}
