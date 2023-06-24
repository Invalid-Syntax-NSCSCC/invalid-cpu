package memory.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class TlbMaintenanceNdPort extends Bundle {
  val isInvalidate   = Bool()
  val isSearch       = Bool()
  val isRead         = Bool()
  val isWrite        = Bool()
  val isFill         = Bool()
  val virtAddr       = UInt(Width.Mem.addr)
  val invalidateInst = UInt(Width.Tlb.op)
  val registerAsid   = UInt(Width.Csr.asid)
}

object TlbMaintenanceNdPort {
  def default = (new TlbMaintenanceNdPort).Lit(
    _.isInvalidate -> false.B,
    _.isSearch -> false.B,
    _.isRead -> false.B,
    _.isWrite -> false.B,
    _.isFill -> false.B,
    _.registerAsid -> 0.U(Width.Csr.asid),
    _.virtAddr -> 0.U(Width.Mem.addr),
    _.invalidateInst -> 0.U(Width.Tlb.op)
  )
}
