package frontend.bpu.components.Bundles

import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
class FtbEntryPort extends Bundle {
  val valid            = Input(Bool())
  val isCrossCacheline = Input(Bool())
  val branchType       = Input(UInt(2.W))

  // Virtual tag, pc[1:0] is always 0, so not used in index or tag
  val tag                = Input(UInt((spec.Width.Mem._addr - 3 - log2Ceil(Param.BPU.FTB.nset)).W));
  val jumpTargetAddress  = Input(UInt(spec.Width.Mem.addr))
  val fallThroughAddress = Input(UInt(spec.Width.Mem.addr))
}

object FtbEntryPort {
  val bitsLength =
    1 + 1 + 2 + spec.Width.Mem._addr - 3 - log2Ceil(Param.BPU.FTB.nset) + 2 * spec.Width.Mem._addr
  def default = (new FtbEntryPort).Lit(
    _.valid -> false.B,
    _.isCrossCacheline -> false.B,
    _.branchType -> 0.U(2.W),
    _.tag -> 0.U((spec.Width.Mem._addr - 3 - log2Ceil(Param.BPU.FTB.nset)).W),
    _.jumpTargetAddress -> 0.U(spec.Width.Mem.addr),
    _.fallThroughAddress -> 0.U(spec.Width.Mem.addr)
  )
}
