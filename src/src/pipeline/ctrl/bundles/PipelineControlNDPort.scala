package pipeline.ctrl.bundles

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class PipelineControlNDPort extends Bundle {
  val flush = Bool()
  val clear = Bool()
  val stall = Bool()
}

object PipelineControlNDPort {
  val default = (new PipelineControlNDPort).Lit(
    _.flush -> false.B,
    _.clear -> false.B,
    _.stall -> true.B
  )
}