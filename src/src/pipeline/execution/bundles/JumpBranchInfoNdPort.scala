package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class JumpBranchInfoNdPort extends Bundle {
  val en     = Bool()
  val pcAddr = UInt(Width.Reg.data)
}

object JumpBranchInfoNdPort {
  val default = (new JumpBranchInfoNdPort).Lit(
    _.en -> false.B,
    _.pcAddr -> zeroWord
  )
}
