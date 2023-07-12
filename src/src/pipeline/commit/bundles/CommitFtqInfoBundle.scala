package pipeline.commit.bundles

import chisel3._
import spec._

class CommitFtqInfoBundle extends Bundle {
  val isBranch        = Bool()
  val branchType      = UInt(Param.BPU.BranchType.width.W)
  val isBranchSuccess = Bool()
  val isPredictError  = Bool()
}

object CommitFtqInfoBundle {
  def default = 0.U.asTypeOf(new CommitFtqInfoBundle)
}
