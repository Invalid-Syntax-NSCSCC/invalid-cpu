package frontend.bpu.bundles

import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
class FtqBranchMetaEntry(
  addr: Int = wordLength)
    extends Bundle {
  val ftbDirty        = Bool()
  val jumpTargetAddr  = UInt(addr.W)
  val fallThroughAddr = UInt(addr.W)
}

object FtqBranchMetaEntry {
  def default = 0.U.asTypeOf(new FtqBranchMetaEntry)
}
