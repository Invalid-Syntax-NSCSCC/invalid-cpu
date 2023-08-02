package common

package common

import chisel3._
import chisel3.util._
import pipeline.common.bundles.BaseStageIo

abstract class BaseStageWOOutReg[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  => InT,
  outNdFactory: => OutT,
  blankIn:      => InT,
  peerFactory:  => Option[PT] = None)
    extends Module {
  val io = IO(new BaseStageIo(inNdFactory, outNdFactory, peerFactory))

  private val savedIn = RegInit(blankIn)
  savedIn := savedIn
  protected val isComputed:     Bool = WireDefault(true.B)
  protected val isLastComputed: Bool = RegNext(isComputed, true.B)
  protected val selectedIn = io.in.bits
//   protected val selectedIn: InT = Mux(
//     io.isFlush,
//     blankIn,
//     Mux(
//       io.in.ready,
//       Mux(
//         io.in.valid,
//         io.in.bits,
//         blankIn
//       ),
//       savedIn
//     )
//   )

  // Handle input
  protected val inReady = isLastComputed && io.out.ready
  io.in.ready := inReady
  when(io.in.valid && io.in.ready) {
    savedIn := io.in.bits
  }

  // Invalidate `savedIn` when computed
  when(isComputed) {
    savedIn := blankIn
  }

  // Handle flush (queue is already handled)
  when(io.isFlush) {
    io.in.ready    := false.B
    io.out.valid   := false.B
    isLastComputed := true.B
    savedIn        := blankIn
  }
}
