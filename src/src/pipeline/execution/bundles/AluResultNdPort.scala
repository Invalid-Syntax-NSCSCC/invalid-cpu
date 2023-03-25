package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class AluResultNdPort extends Bundle {
  val logic          = UInt(Width.Reg.data)
  val shift          = UInt(Width.Reg.data)
  val arithmetic     = UInt(Width.Reg.data)
  val jumpBranchInfo = new JumpBranchInfoNdPort
}

object AluResultNdPort {
  val default = (new AluResultNdPort).Lit(
  _.logic -> zeroWord,
  _.shift -> zeroWord,
  _.arithmetic -> zeroWord,
  _.jumpBranchInfo -> JumpBranchInfoNdPort.default
  )
}