package pipeline.complex.pmu.bundles

import chisel3._

class PmuBranchMisPredictExeNdPort extends Bundle {
  val directionMispredict = Bool()
  val targetMispredict    = Bool()
}