package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals._

class ScoreboardChangeNdPort(addrWidth: internal.firrtl.Width = Width.Reg.addr) extends Bundle {
  val en   = Bool()
  val addr = UInt(addrWidth)
}

object ScoreboardChangeNdPort {
  val default = (new ScoreboardChangeNdPort).Lit(
    _.en -> false.B,
    _.addr -> zeroWord
  )
}
