package pmu.bundles

import spec._
import chisel3._

class PmuBranchMisPredictExeNdPort extends Bundle {
  val directionMispredict = Bool()
  val targetMispredict    = Bool()
}
