package pipeline.common

import chisel3._
import chisel3.util._
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
    val enqIncResults = Output(Vec(enqMaxNum + 1, UInt(log2Ceil(channelNum).W)))
    val deqIncResults = Output(Vec(deqMaxNum + 1, UInt(log2Ceil(channelNum).W)))
    val enq_ptr       = Output(UInt(log2Ceil(channelNum).W))
    val deq_ptr       = Output(UInt(log2Ceil(channelNum).W))
  })

  val storeIns = Wire(Vec(channelNum, (DecoupledIO(elemNdFactory))))
  val storeOuts = VecInit(storeIns.zipWithIndex.map {
    case (in, idx) =>
      Queue(
        in,
        entries        = channelLength,
        pipe           = false,
        flow           = false,
        useSyncReadMem = true,
        flush          = Some(io.isFlush)
      )
  })

  storeIns.foreach { in =>
    in.valid := false.B
    in.bits  := DontCare
  }

  storeOuts.foreach(_.ready := false.B)

  val enq_ptr = Module(new MultiCounter(channelNum, enqMaxNum))
  val deq_ptr = Module(new MultiCounter(channelNum, enqMaxNum))
  enq_ptr.io.flush := io.isFlush
  deq_ptr.io.flush := io.isFlush
  io.enq_ptr       := enq_ptr.io.value
  io.deq_ptr       := deq_ptr.io.value
  io.enqIncResults.zip(enq_ptr.io.incResults).foreach {
    case (dst, src) =>
      dst := src
  }
  io.deqIncResults.zip(deq_ptr.io.incResults).foreach {
    case (dst, src) =>
      dst := src
  }

  enq_ptr.io.inc := io.enqueuePorts.zipWithIndex.map {
    case (enqPort, idx) =>
      // connect
      enqPort <> storeIns(enq_ptr.io.incResults(idx))
      // return
      enqPort.valid && storeIns(enq_ptr.io.incResults(idx)).ready
  }.map(_.asUInt).reduce(_ +& _)

  deq_ptr.io.inc := io.dequeuePorts.zipWithIndex.map {
    case (deqPort, idx) =>
      // connect
      deqPort <> storeOuts(deq_ptr.io.incResults(idx))
      // return
      deqPort.valid && storeOuts(deq_ptr.io.incResults(idx)).ready
  }.map(_.asUInt).reduce(_ +& _)
}
