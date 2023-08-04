package pipeline.common.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class DifftestStoreNdPort extends Bundle {
  val en    = UInt(8.W) // {4'b0, ds_llbit && inst_sc_w, inst_st_w, inst_st_h, inst_st_b}
  val vaddr = UInt(32.W)
  val paddr = UInt(32.W)
  val data  = UInt(32.W)
}

object DifftestStoreNdPort {
  def default = (new DifftestStoreNdPort).Lit(
    _.en -> 0.U,
    _.vaddr -> 0.U,
    _.paddr -> 0.U,
    _.data -> 0.U
  )
}
