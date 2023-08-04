package pipeline.common.bundles

import chisel3._
import spec._

class CommitFtqInfoBundle extends Bundle {
  val isBranch        = Bool()
  val branchType      = UInt(Param.BPU.BranchType.width.W)
  val isBranchSuccess = Bool()
  val isRedirect      = Bool()

  val directionMispredict = if (Param.usePmu) Some(Bool()) else None
  val targetMispredict    = if (Param.usePmu) Some(Bool()) else None
}

object CommitFtqInfoBundle {
  def default = 0.U.asTypeOf(new CommitFtqInfoBundle)
}
