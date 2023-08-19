package pipeline.common.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._
import common.bundles.RfAccessInfoNdPort

class CustomInstInfoBundle extends Bundle {
  val isCustom            = Bool()
  val isCommit            = Bool()
  val op                  = new ExeInst.OpBundle
  val gprWrite            = new RfAccessInfoNdPort
  val gprReadPorts        = Vec(Param.regFileReadNum, new RfAccessInfoNdPort)
  val imm                 = UInt(wordLength.W)
  val hasImm              = Bool()
  val jumpBranchAddr      = UInt(wordLength.W)
  val isIssueMainPipeline = Bool()

  val isInnerJump   = Bool()
  val jumpDirection = Bool()
  val jumpOffset    = UInt(10.W)
}
