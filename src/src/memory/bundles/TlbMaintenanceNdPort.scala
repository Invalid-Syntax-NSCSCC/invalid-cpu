package memory.bundles

import chisel3._
import chisel3.util._
import control.csrRegsBundles.AsidBundle
import spec._

class TlbMaintenanceNdPort extends Bundle {
  val isInvalidate   = Bool()
  val isSearch       = Bool()
  val isRead         = Bool()
  val isWrite        = Bool()
  val isFill         = Bool()
  val virtAddr       = UInt(Width.Mem.addr)
  val invalidateInst = UInt(Width.Tlb.op)
  val registerAsid   = UInt(10.W)
}
