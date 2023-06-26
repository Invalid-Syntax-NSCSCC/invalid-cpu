package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._

class PipelineControlNdPort extends Bundle {
  val flush = Bool()
  val clear = Bool()
  val stall = Bool()
}

object PipelineControlNdPort {
  def default = (new PipelineControlNdPort).Lit(
    _.flush -> false.B,
    _.clear -> false.B,
    _.stall -> false.B
  )
}
