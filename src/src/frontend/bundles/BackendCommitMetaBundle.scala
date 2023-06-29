package frontend.bundles

import chisel3._
import chisel3.util._

class BackendCommitMetaBundle extends Bundle {
  val isBranch       = Bool()
  val branchType     = UInt(2.W)
  val isTaken        = Bool()
  val predictedTaken = Bool() // Comes from bpu
}
