package frontend.bpu.bundles

import chisel3._
import spec._
class FtqBranchMetaEntry(
  addr: Int = wordLength)
    extends Bundle {
  val ftbDirty           = Bool()
  val jumpTargetAddress  = UInt(addr.W)
  val fallThroughAddress = UInt(addr.W)
}

object FtqBranchMetaEntry {
  def default = 0.U.asTypeOf(new FtqBranchMetaEntry)
}
