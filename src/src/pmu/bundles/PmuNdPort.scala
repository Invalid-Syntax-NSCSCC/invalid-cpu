package pmu.bundles

import spec._
import chisel3._

class PmuNdPort extends Bundle {
  val instqueueFull      = Input(Bool())
  val instqueueFullValid = Input(Bool())
  val branchInfo         = Input(new PmuBranchPredictNdPort)
}
