package pmu.bundles

import spec._
import chisel3._

class PmuBranchPredictNdPort extends Bundle {
  val isBranch   = Bool()
  val isRedirect = Bool()
  val branchType = UInt(Param.BPU.BranchType.width.W)
}
