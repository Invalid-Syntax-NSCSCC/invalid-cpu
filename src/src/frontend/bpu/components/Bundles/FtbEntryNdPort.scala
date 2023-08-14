package frontend.bpu.components.Bundles

import chisel3._
import chisel3.util._
import spec._
class FtbEntryNdPort extends Bundle {
  val valid            = Bool()
  val isCrossCacheline = Bool()
  val branchType       = UInt(Param.BPU.BranchType.width.W)
  val fetchLength      = UInt(log2Ceil(Param.fetchInstMaxNum + 1).W)

  // Virtual tag, pc[1:0] is always 0, so not used in index or tag
  val tag            = UInt(Param.BPU.FTB.tagWidth.W)
  val jumpTargetAddr = UInt(spec.Width.Mem.addr)
//  val fallThroughAddr = UInt(spec.Width.Mem.addr)
}

object FtbEntryNdPort {
//  val width =
//    1 + 1 + 2 + spec.Width.Mem._addr - 2 - log2Ceil(Param.BPU.FTB.nset) + 2 * spec.Width.Mem._addr
  val width =
    1 + 1 + Param.BPU.BranchType.width + log2Ceil(
      Param.fetchInstMaxNum + 1
    ) + Param.BPU.FTB.tagWidth + spec.Width.Mem._addr
  def default = 0.U.asTypeOf(new FtbEntryNdPort)
}
