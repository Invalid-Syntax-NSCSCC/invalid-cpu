package pipeline.simple.bundles

import chisel3._
import spec._

class MainExeBranchInfoBundle extends Bundle {
  val isBranch        = Bool()
  val branchType      = UInt(Param.BPU.BranchType.width.W)
  val pc              = UInt(Width.Reg.data)
  val predictJumpAddr = UInt(Width.Reg.data)
}
