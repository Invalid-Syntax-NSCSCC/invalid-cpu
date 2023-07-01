package pipeline.commit.bundles

import chisel3._

class CommitFtqInfoBundle extends Bundle {
  val isBranch        = Bool()
  val branchType      = UInt(2.W)
  val isBranchSuccess = Bool()
}

object CommitFtqInfoBundle {
  def default = 0.U.asTypeOf(new CommitFtqInfoBundle)
}
