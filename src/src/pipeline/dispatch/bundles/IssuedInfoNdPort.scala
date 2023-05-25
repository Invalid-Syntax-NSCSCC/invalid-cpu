package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._
import pipeline.writeback.bundles.InstInfoNdPort

class IssuedInfoNdPort extends Bundle {
  val preExeInstInfo = new PreExeInstNdPort
  val instInfo       = new InstInfoNdPort
}

object IssuedInfoNdPort {
  def default = (new IssuedInfoNdPort).Lit(
    _.preExeInstInfo -> PreExeInstNdPort.default,
    _.instInfo -> InstInfoNdPort.default
  )
}
