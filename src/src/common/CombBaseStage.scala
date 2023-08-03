package common

import chisel3._
import common.bundles.BaseStagePort

abstract class CombBaseStage[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  => InT,
  outNdFactory: => OutT,
  blankIn:      => InT,
  peerFactory:  => Option[PT] = None)
    extends Module {
  val io = IO(new BaseStagePort(inNdFactory, outNdFactory, peerFactory))

  protected val isComputed:     Bool = WireDefault(true.B)
  protected val isLastComputed: Bool = RegNext(isComputed, true.B)
  protected val selectedIn = io.in.bits

  // Handle input
  protected val inReady = isLastComputed && io.out.ready
  io.in.ready := inReady

  // Handle flush (queue is already handled)
  when(io.isFlush) {
    io.in.ready    := false.B
    io.out.valid   := false.B
    isLastComputed := true.B
  }
}
