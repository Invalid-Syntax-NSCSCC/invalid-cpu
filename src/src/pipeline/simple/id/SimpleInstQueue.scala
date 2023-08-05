package pipeline.simple.id

import spec._
import chisel3.util._
import chisel3._
import pipeline.common.bundles.InstQueueEnqNdPort
import pipeline.common.bundles.FetchInstInfoBundle
import common.DistributedQueue

class SimpleInstQueue(
  queueLength: Int = Param.instQueueLength,
  channelNum:  Int = Param.instQueueChannelNum,
  fetchNum:    Int = Param.fetchInstMaxNum,
  issueNum:    Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    val isFlush     = Input(Bool())
    val enqueuePort = Flipped(Decoupled(new InstQueueEnqNdPort))

    // `InstQueue` -> `IssueStage`
    val dequeuePorts = Vec(
      issueNum,
      Decoupled(new FetchInstInfoBundle)
    )
  })

  require(queueLength > fetchNum)
  require(queueLength > issueNum)
  require(channelNum >= fetchNum)
  require(channelNum >= issueNum)
  require(queueLength % channelNum == 0)

  val instQueue = Module(
    new DistributedQueue(
      fetchNum,
      issueNum,
      channelNum,
      queueLength / channelNum,
      new FetchInstInfoBundle,
      flow = !Param.instQueueCombineSel
    )
  )

  instQueue.io.enqueuePorts.zipWithIndex.foreach {
    case (enq, idx) =>
      enq.valid := io.enqueuePort.bits.enqInfos(idx).valid && io.enqueuePort.ready && io.enqueuePort.valid
      enq.bits  := io.enqueuePort.bits.enqInfos(idx).bits
  }
  io.enqueuePort.ready := instQueue.io.enqueuePorts.map(_.ready).reduce(_ && _)
  instQueue.io.isFlush := io.isFlush

  io.dequeuePorts.zip(instQueue.io.dequeuePorts).foreach {
    case (dst, src) =>
      dst <> src
  }

}
