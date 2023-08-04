package pipeline.simple.pmu.bundles

import chisel3._

class PmuBranchMisPredictExeNdPort extends Bundle {
  val directionMispredict = Bool()
  val targetMispredict    = Bool()
}
