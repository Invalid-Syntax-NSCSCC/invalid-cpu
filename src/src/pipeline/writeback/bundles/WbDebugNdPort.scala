package pipeline.writeback.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import spec._

class WbDebugNdPort extends Bundle {
  val pc   = UInt(Width.Reg.data)
  val inst = UInt(Width.Reg.data)
  val exception = UInt(CsrRegs.Exception.width)
}

object WbDebugNdPort {
  val default = (new WbDebugNdPort).Lit(
    _.pc -> 0.U,
    _.inst -> 0.U
  )
}
