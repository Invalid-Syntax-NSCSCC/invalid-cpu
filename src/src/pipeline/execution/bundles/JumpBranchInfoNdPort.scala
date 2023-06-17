package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals._

class JumpBranchInfoNdPort extends Bundle {
  val en     = Bool()
  val pcAddr = UInt(Width.Reg.data)
}

object JumpBranchInfoNdPort {
  def default = (new JumpBranchInfoNdPort).Lit(
    _.en -> false.B,
    _.pcAddr -> zeroWord
  )
}
