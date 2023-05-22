package memory.bundles

import chisel3._
import chisel3.util._
import memory.enums.TlbMemType
import spec._

class TlbTransPort extends Bundle {
  val virtAddr  = Input(UInt(Width.Mem.addr))
  val memType   = Input(TlbMemType())
  val physAddr  = Output(UInt(Width.Mem.addr))
  val exception = Valid(UInt(Width.Csr.exceptionIndex))
}