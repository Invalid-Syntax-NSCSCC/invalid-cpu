package control.bundles

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class PipelineControlNdPort extends Bundle {
  val flush = Bool()
  val clear = Bool()
  val stall = Bool()
}

object PipelineControlNdPort {
  val default = (new PipelineControlNdPort).Lit(
    _.flush -> false.B,
    _.clear -> false.B,
    _.stall -> false.B
  )
}
