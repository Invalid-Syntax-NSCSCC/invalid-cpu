package pipeline.complex.pmu.bundles

import chisel3._
import spec._

class PmuBranchPredictNdPort extends Bundle {
  val isBranch            = Bool()
  val isRedirect          = Bool()
  val branchType          = UInt(Param.BPU.BranchType.width.W)
  val directionMispredict = Bool()
  val targetMispredict    = Bool()
}
