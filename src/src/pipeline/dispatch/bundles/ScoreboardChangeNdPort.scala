package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import spec._

class ScoreboardChangeNdPort(addrWidth: internal.firrtl.Width = Width.Reg.addr) extends Bundle {
  val en   = Bool()
  val addr = UInt(addrWidth)
}
