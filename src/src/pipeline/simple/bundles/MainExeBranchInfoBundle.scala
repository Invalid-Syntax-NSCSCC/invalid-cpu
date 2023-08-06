package pipeline.simple.bundles

import chisel3._
import spec._

class MainExeBranchInfoBundle extends Bundle {
  val pc              = UInt(Width.Reg.data)
  val predictJumpAddr = UInt(Width.Reg.data)
}
