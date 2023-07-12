package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class ScoreboardChangeNdPort(addrWidth: internal.firrtl.Width = Width.Reg.addr) extends Bundle {
  val en = Bool()
}

object ScoreboardChangeNdPort {
  def default = (new ScoreboardChangeNdPort).Lit(
    _.en -> false.B
  )
}
