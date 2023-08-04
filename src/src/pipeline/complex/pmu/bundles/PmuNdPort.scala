package pipeline.complex.pmu.bundles

import chisel3._

class PmuNdPort extends Bundle {
  val instqueueFull      = Input(Bool())
  val instqueueFullValid = Input(Bool())
  val branchInfo         = Input(new PmuBranchPredictNdPort)
}
