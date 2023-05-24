package pipeline.queue

import chisel3._
import chisel3.util._
import control.bundles.PipelineControlNdPort
import pipeline.dispatch.bundles.InstInfoBundle
import spec._

// 尝试写多发射的queue，未接入，不用管它
// assert: enqueuePorts总是最低的几位有效
class MultiInstQueue(
  val queueLength: Int = Param.instQueueLength,
  val issueNum:    Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    // val isFlush     = Input(Bool())
    val pipelineControlPort = Input(new PipelineControlNdPort)
    val enqueuePorts        = Vec(issueNum, Flipped(Decoupled(new InstInfoBundle)))

    // `InstQueue` -> `IssueStage`
    val dequeuePorts = Vec(issueNum, Decoupled(new InstInfoBundle))
  })

//   val queue =
//     Queue(io.enqueuePorts(0), entries = queueLength, pipe = false, flow = true, flush = Some(io.pipelineControlPort.flush))

//   io.dequeuePort <> queue
  val isFull  = RegInit(false.B)
  val isEmpty = RegInit(true.B)

  val ram = RegInit(VecInit(Seq.fill(queueLength)(zeroWord)))

  val enq_ptr = Counter(queueLength)
  val deq_ptr = Counter(queueLength)
  val storeNum = WireDefault(
    Mux(
      enq_ptr.value > deq_ptr.value,
      enq_ptr.value - deq_ptr.value,
      (queueLength.U - deq_ptr.value) + enq_ptr.value
    )
  )
  val emptyNum = WireDefault(queueLength.U - storeNum)

  // enqueue
  val numWidth: Int = log2Ceil(issueNum)
  val enqueueNum = io.enqueuePorts.map(_.valid).map(_.asUInt).reduce(_ + _)

  // dequeue
  val dequeueNum = io.dequeuePorts.map(_.valid).map(_.asUInt).reduce(_ + _)

  when(io.pipelineControlPort.flush) {
    ram.foreach(_ := zeroWord)
  }
}
