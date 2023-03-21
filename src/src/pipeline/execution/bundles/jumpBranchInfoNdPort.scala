package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._

class jumpBranchInfoNdPort extends Bundle {
  val en     = Bool()
  val pcAddr = UInt(Width.Reg.data)
}
