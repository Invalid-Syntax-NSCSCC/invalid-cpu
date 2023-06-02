package pipeline.common.bundles

import chisel3._
import chisel3.util._

class MultiBaseStageIo[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  InT,
  outNdFactory: OutT,
  peerFactory:  => Option[PT],
  inNum:        Int,
  outNum:       Int)
    extends Bundle {
  val ins     = Vec(inNum, Flipped(Decoupled(inNdFactory)))
  val outs    = Vec(outNum, Decoupled(outNdFactory))
  val peer    = peerFactory
  val isFlush = Input(Bool())
}
