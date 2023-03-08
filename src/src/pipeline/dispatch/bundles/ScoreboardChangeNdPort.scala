package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import spec._

class ScoreboardChangeNdPort extends Bundle {
  val en   = Bool()
  val addr = UInt(Width.Reg.addr)
}
