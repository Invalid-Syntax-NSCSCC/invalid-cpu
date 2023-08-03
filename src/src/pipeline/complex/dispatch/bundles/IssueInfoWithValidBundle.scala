package pipeline.complex.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import pipeline.complex.dispatch.RegReadNdPort

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
