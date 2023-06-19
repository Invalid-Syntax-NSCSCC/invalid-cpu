package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.dispatch.RegReadNdPort
import chisel3.experimental.BundleLiterals._

class RegReadPortWithValidBundle extends Bundle {
  val valid     = Bool()
  val issueInfo = new RegReadNdPort
}

object RegReadPortWithValidBundle {
  def default = (new RegReadPortWithValidBundle).Lit(
    _.valid -> false.B,
    _.issueInfo -> RegReadNdPort.default
  )
}
