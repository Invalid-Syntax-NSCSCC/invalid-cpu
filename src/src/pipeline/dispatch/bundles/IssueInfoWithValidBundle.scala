package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import pipeline.writeback.bundles.InstInfoNdPort
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class IssueInfoWithValidBundle extends Bundle {
  val valid     = Bool()
  val instInfo  = new InstInfoNdPort
  val issueInfo = new IssuedInfoNdPort
}

object IssueInfoWithValidBundle {
  val default = (new IssueInfoWithValidBundle).Lit(
    _.valid -> false.B,
    _.instInfo -> InstInfoNdPort.default,
    _.issueInfo -> IssuedInfoNdPort.default
  )
}
