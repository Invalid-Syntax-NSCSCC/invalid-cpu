package pipeline.commit.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class DifftestTlbFillNdPort extends Bundle {
  val valid     = Bool()
  val fillIndex = UInt(Width.Reg.addr)
}

object DifftestTlbFillNdPort {
  def default = (new DifftestTlbFillNdPort).Lit(
    _.valid -> false.B,
    _.fillIndex -> 0.U
  )
}
