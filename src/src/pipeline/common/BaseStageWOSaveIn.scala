package pipeline.common

import chisel3._
import chisel3.util._
import pipeline.common.bundles.BaseStageIo

abstract class BaseStageWOSaveIn[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  => InT,
  outNdFactory: => OutT,
  blankIn:      => InT,
  peerFactory:  => Option[PT] = None)
    extends Module {
  val io = IO(new BaseStageIo(inNdFactory, outNdFactory, peerFactory))

  private val queueSize = 1

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
  protected val outReady = ((lastResultOut.ready && !lastResultOut.valid) || io.out.ready)
  io.in.ready := outReady

  // Handle flush (queue is already handled)
  when(io.isFlush) {
    io.in.ready  := false.B
    io.out.valid := false.B
  }
}
