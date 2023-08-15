package frontend.bpu.bundles

import chisel3._
import chisel3.util.log2Ceil
import spec._
class FtqBranchMetaEntry(
  addr: Int = wordLength)
    extends Bundle {
  val ftbDirty        = Bool()
  val jumpTargetAddr  = UInt(addr.W)
  val fetchLength      = UInt(log2Ceil(Param.fetchInstMaxNum + 1).W)
}

object FtqBranchMetaEntry {
  def default = 0.U.asTypeOf(new FtqBranchMetaEntry)
}
