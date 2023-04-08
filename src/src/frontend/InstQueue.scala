package frontend

import chisel3._
import chisel3.util._

import spec._
import pipeline.dispatch.bundles.InstInfoBundle
import control.bundles.PipelineControlNDPort

class InstQueue(val queueLength: Int = Param.instQueueLength) extends Module {
  val io = IO(new Bundle {
    // val isFlush     = Input(Bool())
    val pipelineControlPort = Input(new PipelineControlNDPort)
    val enqueuePort         = Flipped(Decoupled(new InstInfoBundle))

    // `InstQueue` -> `IssueStage`
    val dequeuePort = Decoupled(new InstInfoBundle)
  })

  val queue =
    Queue(io.enqueuePort, entries = queueLength, pipe = false, flow = true, flush = Some(io.pipelineControlPort.flush))

  io.dequeuePort <> queue
}
