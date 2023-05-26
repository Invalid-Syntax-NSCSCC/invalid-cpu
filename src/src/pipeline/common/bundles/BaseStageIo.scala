package pipeline.common.bundles

import chisel3._
import chisel3.util._

class BaseStageIo[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  InT,
  outNdFactory: OutT,
  peerFactory:  => Option[PT])
    extends Bundle {
  val in      = Flipped(Decoupled(inNdFactory))
  val out     = Decoupled(outNdFactory)
  val peer    = peerFactory
  val isFlush = Input(Bool())
}
