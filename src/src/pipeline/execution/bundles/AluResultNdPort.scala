package pipeline.execution.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class AluResultNdPort extends Bundle {
  val logic          = UInt(Width.Reg.data)
  val shift          = UInt(Width.Reg.data)
  val arithmetic     = UInt(Width.Reg.data)
  val jumpBranchInfo = new JumpBranchInfoNdPort
}

object AluResultNdPort {
  def default = (new AluResultNdPort).Lit(
    _.logic -> zeroWord,
    _.shift -> zeroWord,
    _.arithmetic -> zeroWord,
    _.jumpBranchInfo -> JumpBranchInfoNdPort.default
  )
}
