package pipeline.common

import chisel3._
import chisel3.util._
import spec._
import utils.MultiCounter

class DistributedQueue[ElemT <: Data](
  enqMaxNum:     Int,
  deqMaxNum:     Int,
  channelNum:    Int,
  channelLength: Int,
  elemNdFactory: => ElemT,
  blankElem:     => ElemT)
    extends Module {

  require(channelNum >= enqMaxNum)
  require(channelNum >= deqMaxNum)

  val io = IO(new Bundle {
    val isFlush      = Input(Bool())
    val enqueuePorts = Vec(enqMaxNum, Flipped(Decoupled(elemNdFactory)))
    val dequeuePorts = Vec(deqMaxNum, Decoupled(elemNdFactory))

    // deq_ptr -> enq_ptr
    val enqIncResults = Output(Vec(channelNum + 1, UInt(log2Ceil(channelNum).W)))
    val deqIncResults = Output(Vec(channelNum + 1, UInt(log2Ceil(channelNum).W)))
    val enq_ptr       = Output(UInt(log2Ceil(channelNum).W))
    val deq_ptr       = Output(UInt(log2Ceil(channelNum).W))
  })

  val queues = VecInit(
    Seq.fill(channelNum)(
      Queue(
        Decoupled(elemNdFactory),
        entries = channelLength,
        pipe    = false,
        flow    = false,
        flush   = Some(io.isFlush)
      )
    )
  )

  val enq_ptr = Module(new MultiCounter(channelLength, enqMaxNum))
  val deq_ptr = Module(new MultiCounter(channelLength, enqMaxNum))
  enq_ptr.io.flush := io.isFlush
  deq_ptr.io.flush := io.isFlush
  io.enq_ptr       := enq_ptr.io.value
  io.deq_ptr       := deq_ptr.io.value

  enq_ptr.io.inc := io.enqueuePorts.zipWithIndex.map {
    case (enqPort, idx) =>
      // connect
      enqPort <> queues(enq_ptr.io.incResults(idx))
      // return
      enqPort.valid && queues(enq_ptr.io.incResults(idx)).ready
  }.map(_.asUInt).reduce(_ +& _)

  deq_ptr.io.inc := io.dequeuePorts.zipWithIndex.map {
    case (deqPort, idx) =>
      // connect
      deqPort <> queues(deq_ptr.io.incResults(idx))
      // return
      deqPort.valid && queues(deq_ptr.io.incResults(idx)).ready
  }.map(_.asUInt).reduce(_ +& _)
}
