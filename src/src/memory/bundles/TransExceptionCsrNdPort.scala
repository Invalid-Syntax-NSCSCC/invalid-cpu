package memory.bundles

import chisel3._
import chisel3.util._
import spec._

class TransExceptionCsrNdPort extends Bundle {
  val vppn = UInt(Width.Tlb.vppn)
}

object TransExceptionCsrNdPort {
  def default = 0.U.asTypeOf(new TransExceptionCsrNdPort)
}
