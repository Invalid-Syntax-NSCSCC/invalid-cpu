package frontend

import chisel3._
import chisel3.util._

import spec._
import pipeline.dispatch.bundles.InstInfoBundle
import control.bundles.PipelineControlNDPort
import frontend._

// 尝试写双发射的queue，未接入，不用管它
// assert: enqueuePorts总是最低的几位有效
class BiInstQueue(
  val queueLength: Int = Param.instQueueLength,
  val issueNum:    Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    // val isFlush     = Input(Bool())
    val pipelineControlPort = Input(new PipelineControlNDPort)
    val enqueuePorts        = Vec(issueNum, Flipped(Decoupled(new InstInfoBundle)))

    // `InstQueue` -> `IssueStage`
    val dequeuePorts = Vec(issueNum, Decoupled(new InstInfoBundle))
  })
  require(issueNum == 2)
//   val queue =
//     Queue(io.enqueuePorts(0), entries = queueLength, pipe = false, flow = true, flush = Some(io.pipelineControlPort.flush))

//   io.dequeuePort <> queue

  val ram     = RegInit(VecInit(Seq.fill(queueLength)(InstInfoBundle.default)))
  val enq_ptr = Module(new BiCounter(queueLength))
  val deq_ptr = Module(new BiCounter(queueLength))

  enq_ptr.io.inc := 0.U
  deq_ptr.io.inc := 0.U

  val maybeFull = RegInit(false.B)
  val ptrMatch  = enq_ptr.io.value === deq_ptr.io.value
  val isEmpty   = ptrMatch && !maybeFull
  val isFull    = ptrMatch && maybeFull

  val storeNum = WireDefault(
    Mux(
      enq_ptr.io.value > deq_ptr.io.value,
      enq_ptr.io.value - deq_ptr.io.value,
      (queueLength.U - deq_ptr.io.value) + enq_ptr.io.value
    )
  )
  val emptyNum = WireDefault(queueLength.U - storeNum)

  val isEmptyByOne = WireDefault(storeNum === 1.U)
  val isFullByOne  = WireDefault(emptyNum === 1.U)

  io.enqueuePorts(0).ready := !isFull
  io.enqueuePorts(1).ready := !isFullByOne

  io.dequeuePorts(0).valid := !isEmpty
  io.dequeuePorts(1).valid := !isEmptyByOne

  // enqueue
  val numWidth: Int = log2Ceil(issueNum)

  val enqEn = (io.enqueuePorts.map(port => (port.ready && port.valid)))
  // val enqueueNum = io.enqueuePorts.map(_.valid).map(_.asUInt).reduce(_ + _)
  // 优化
  val enqueueNum = Cat(
    enqEn(0) & enqEn(1),
    enqEn(0) ^ enqEn(1)
  )

  // dequeue
  val deqEn = (io.dequeuePorts.map(port => (port.ready && port.valid)))
  // val dequeueNum = io.dequeuePorts.map(_.valid).map(_.asUInt).reduce(_ + _)
  val dequeueNum = Cat(
    deqEn(0) & deqEn(1),
    deqEn(0) ^ deqEn(1)
  )

  when(enqueueNum > dequeueNum) {
    maybeFull := true.B
  }.elsewhen(enqueueNum < dequeueNum) {
    maybeFull := false.B
  }

  when(!isFull) {
    when(enqueueNum(1)) {
      // 请求入队两个
      ram(enq_ptr.io.value) := io.enqueuePorts(0).bits
      when(isFullByOne) {
        // 只剩一个位置
        enq_ptr.io.inc := 1.U
      }.otherwise {
        // 直接加两个
        ram(enq_ptr.io.value + 1.U) := io.enqueuePorts(1).bits
        enq_ptr.io.inc              := 2.U
      }
    }.elsewhen(enqueueNum(0)) {
      // 请求入队一个
      ram(enq_ptr.io.value) := io.enqueuePorts(0).bits
      enq_ptr.io.inc        := 1.U
    }
  }

  when(!isEmpty) {
    when(dequeueNum(1)) {
      // 请求出队两个
      when(isEmptyByOne) {
        // 只有一条指令
        deq_ptr.io.inc := 1.U
      }.otherwise {
        // 正常出队两条
        deq_ptr.io.inc := 2.U
      }
    }.elsewhen(dequeueNum(0)) {
      // 请求出队一个
      deq_ptr.io.inc := 1.U
    }
  }

  io.dequeuePorts(0).bits := ram(deq_ptr.io.value)
  io.dequeuePorts(1).bits := ram(deq_ptr.io.value + 1.U)

  when(io.pipelineControlPort.flush) {
    ram.foreach(_ := InstInfoBundle.default)
  }
}
