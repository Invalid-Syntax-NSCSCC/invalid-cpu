package pipeline.commit.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import spec._

class DifftestLoadNdPort extends Bundle {
  val en    = UInt(8.W) // {2'b0, inst_ll_w, inst_ld_w, inst_ld_hu, inst_ld_h, inst_ld_bu, inst_ld_b}
  val vaddr = UInt(32.W)
  val paddr = UInt(32.W)
}

object DifftestLoadNdPort {
  def default = (new DifftestLoadNdPort).Lit(
    _.en -> 0.U,
    _.vaddr -> 0.U,
    _.paddr -> 0.U
  )
}
