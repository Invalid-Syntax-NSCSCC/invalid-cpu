package frontend.bpu.components.Bundles

import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
class FtbEntryNdPort extends Bundle {
  val valid            = Bool()
  val isCrossCacheline = Bool()
  val branchType       = UInt(2.W)

  // Virtual tag, pc[1:0] is always 0, so not used in index or tag
  val tag                = UInt((spec.Width.Mem._addr - 3 - log2Ceil(Param.BPU.FTB.nset)).W)
  val jumpTargetAddress  = UInt(spec.Width.Mem.addr)
  val fallThroughAddress = UInt(spec.Width.Mem.addr)
}

object FtbEntryNdPort {
  val bitsLength =
    1 + 1 + 2 + spec.Width.Mem._addr - 3 - log2Ceil(Param.BPU.FTB.nset) + 2 * spec.Width.Mem._addr
  def default = (new FtbEntryNdPort).Lit(
    _.valid -> false.B,
    _.isCrossCacheline -> false.B,
    _.branchType -> 0.U(2.W),
    _.tag -> 0.U((spec.Width.Mem._addr - 3 - log2Ceil(Param.BPU.FTB.nset)).W),
    _.jumpTargetAddress -> 0.U(spec.Width.Mem.addr),
    _.fallThroughAddress -> 0.U(spec.Width.Mem.addr)
  )
}
