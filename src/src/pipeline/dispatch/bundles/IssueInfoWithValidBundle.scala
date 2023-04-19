package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import pipeline.writeback.bundles.InstInfoNdPort

class IssueInfoWithValidBundle extends Bundle {
  val valid     = Bool()
  val instInfo  = new InstInfoNdPort
  val issueInfo = new IssuedInfoNdPort
}
