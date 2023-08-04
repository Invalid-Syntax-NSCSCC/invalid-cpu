package common

import chisel3._
import chisel3.util._
import common.bundles.BaseStagePort

abstract class BaseStage[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  => InT,
  outNdFactory: => OutT,
  blankIn:      => InT,
  peerFactory:  => Option[PT] = None)
    extends Module {
  val io = IO(new BaseStagePort(inNdFactory, outNdFactory, peerFactory))

  private val queueSize = 1

  private val savedIn = RegInit(blankIn)
  savedIn := savedIn
  protected val isComputed:     Bool = WireDefault(true.B)
  protected val isLastComputed: Bool = RegNext(isComputed, true.B)
  protected val selectedIn: InT = Mux(
    io.isFlush,
    blankIn,
    Mux(
      io.in.ready,
      Mux(
        io.in.valid,
        io.in.bits,
        blankIn
      ),
      savedIn
    )
  )

  // You should only focus on what `selectedIn` has to compute and make decision.
  // If the computation needs more than one cycle, which means `isComplete` will be false for a while,
  //   then `selectedIn` will be kept automatically for these cycle, until `isLastComputed` is true.
  // If there is no input available and the stage is idle, then `selectedIn` will be `blankIn`,
  //   which should be an invalid input.

  // When current task is computed, then set `isComputed` to true, otherwise false.
  // Please note that the default value of `isComputed` is true, and it should be true in the same cycle of computed.
  // And you may find `isLastComputed` useful.

  // Push the computed result to `outQueue` via `resultOutReg` interface.
  // For example: When computed result 1.U, then
  //   resultOutReg.valid := true.B
  //   resultOutReg.bits  := 1.U
  // Please note that `resultOutReg.ready` is guaranteed to be true by the following code.
  // But you should take care of when to put result.
  // One more thing, you should also check whether `selectedIn` is invalid. Don't put invalid result to `resultOutReg`.

  // Always remember to handle flush for self-defined registers.

  // Please refer to `DummyStage` in `BaseStageSpec` for more usage information.
  // Recommend to check out the waveform to have a better understanding.

  protected val resultOutReg: ValidIO[OutT] = RegInit(0.U.asTypeOf(ValidIO(outNdFactory)))
  resultOutReg.valid := false.B
  private val lastResultOut = Wire(Decoupled(outNdFactory))
  lastResultOut.valid := resultOutReg.valid
  lastResultOut.bits  := resultOutReg.bits
  private val outQueue = Queue(
    lastResultOut,
    entries = queueSize,
    pipe    = false,
    flow    = true,
    flush   = Some(io.isFlush)
  )

  // Handle output
  io.out <> outQueue

  // Handle input
  protected val inReady = isLastComputed && ((lastResultOut.ready && !lastResultOut.valid) || io.out.ready)
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
