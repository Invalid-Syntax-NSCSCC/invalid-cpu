package pipeline.simple.bundles

import chisel3._
import spec._

class CommitFtqInfoBundle extends Bundle {
  val isRedirect = Bool()

  val isBranch            = Option.when(Param.usePmu)(Bool())
  val branchType          = Option.when(Param.usePmu)(UInt(Param.BPU.BranchType.width.W))
  val directionMispredict = if (Param.usePmu) Some(Bool()) else None
  val targetMispredict    = if (Param.usePmu) Some(Bool()) else None
}

object CommitFtqInfoBundle {
  def default = 0.U.asTypeOf(new CommitFtqInfoBundle)
}
