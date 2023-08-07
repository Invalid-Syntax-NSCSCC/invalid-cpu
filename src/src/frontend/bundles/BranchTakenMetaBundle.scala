package frontend.bundles

import chisel3._

class BranchTakenMetaBundle extends Bundle {
  val branchType     = UInt(2.W)
  val isTaken        = Bool()
  val predictedTaken = Bool() // Comes from bpu
}
