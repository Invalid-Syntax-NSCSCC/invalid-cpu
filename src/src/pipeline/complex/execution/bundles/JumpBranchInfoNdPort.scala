package pipeline.complex.execution.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

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
