package frontend.bpu.components.Bundles

import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
class FtbEntryNdPort extends Bundle {
  val valid            = Bool()
  val isCrossCacheline = Bool()
  val branchType       = UInt(Param.BPU.BranchType.width.W)

  // Virtual tag, pc[1:0] is always 0, so not used in index or tag
  val tag                    = UInt((spec.Width.Mem._addr - 2 - log2Ceil(Param.BPU.FTB.nset)).W)
  val partialFallThroughAddr = UInt((Param.Width.ICache._byteOffset).W)
  // in order to reduce cost ,only use fallThroughAddr(_byteOffset,2)
  val jumpTargetAddr = UInt(spec.Width.Mem.addr)
}

object FtbEntryNdPort {
  val width =
    1 + 1 + 2 + (spec.Width.Mem._addr - 2 - log2Ceil(
      Param.BPU.FTB.nset
    )) + (Param.Width.ICache._byteOffset) + spec.Width.Mem._addr
  def default = 0.U.asTypeOf(new FtbEntryNdPort)
}
