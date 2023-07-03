package pipeline.common

import chisel3._
import chisel3.util._
import utils.MultiCounter
import spec.Param

class DistributedQueuePlus[ElemT <: Data](
  enqMaxNum:      Int,
  deqMaxNum:      Int,
  channelNum:     Int,
  channelLength:  Int,
  elemNdFactory:  => ElemT,
  blankElem:      => ElemT,
  useSyncReadMem: Boolean = true)
    extends Module {

  require(channelNum >= enqMaxNum)
  require(channelNum >= deqMaxNum)
  require(log2Ceil(channelNum) == log2Floor(channelNum))
  val channelNumWidth = log2Ceil(channelNum)

  val io = IO(new Bundle {
    val isFlush      = Input(Bool())
    val enqueuePorts = Vec(enqMaxNum, Flipped(Decoupled(elemNdFactory)))
    val dequeuePorts = Vec(deqMaxNum, Decoupled(elemNdFactory))

    // deq_ptr -> enq_ptr
    val enqIncResults = Output(Vec(enqMaxNum + 1, UInt(log2Ceil(channelLength * channelNum).W)))
    val deqIncResults = Output(Vec(deqMaxNum + 1, UInt(log2Ceil(channelLength * channelNum).W)))
    // val deqIncResults = Output(Vec(deqMaxNum + 1, UInt(log2Ceil(channelLength * channelNum).W)))
    // val enq_ptr       = Output(UInt(log2Ceil(channelLength * channelNum).W))
    // val deq_ptr       = Output(UInt(log2Ceil(channelLength * channelNum).W))

    val elems    = Output(Vec(channelLength * channelNum, elemNdFactory))
    val setPorts = Input(Vec(channelLength * channelNum, Valid(elemNdFactory)))
  })

  // Fallback
  io.dequeuePorts.foreach(_ <> DontCare)
  io.enqueuePorts.foreach(_.ready := false.B)
  io.dequeuePorts.foreach(_.valid := false.B)

  // val storeIns = Wire(Vec(channelNum, (DecoupledIO(elemNdFactory))))

  val queues = Seq.fill(channelNum)(
    Module(
      new MultiQueue(
        channelLength,
        1,
        1,
        elemNdFactory,
        blankElem,
        writeFirst = false
      )
    )
  )
  queues.foreach(_.io.isFlush := io.isFlush)

  val storeIns  = VecInit(queues.map(_.io.enqueuePorts(0)))
  val storeOuts = VecInit(queues.map(_.io.dequeuePorts(0)))

  for (i <- 0 until channelLength) {
    for (j <- 0 until channelNum) {
      val idx = i * channelLength + j
      io.elems(idx)            := queues(j).io.elems(i)
      queues(j).io.setPorts(i) := io.setPorts(idx)
    }
  }

  storeIns.foreach { in =>
    in.valid := false.B
    in.bits  := DontCare
  }

  storeOuts.foreach(_.ready := false.B)
  if (channelNum == 1) {
    io.enqueuePorts(0) <> storeIns(0)
    io.dequeuePorts(0) <> storeOuts(0)
  } else {
    val enq_ptr = Module(new MultiCounter(channelLength * channelNum, enqMaxNum))
    val deq_ptr = Module(new MultiCounter(channelLength * channelNum, deqMaxNum))

    enq_ptr.io.flush := io.isFlush
    deq_ptr.io.flush := io.isFlush
    // io.enq_ptr       := enq_ptr.io.value
    // io.deq_ptr       := deq_ptr.io.value
    // io.enqIncResults.zip(enq_ptr.io.incResults).foreach {
    //   case (dst, src) =>
    //     dst := src
    // }
    // io.deqIncResults.zip(deq_ptr.io.incResults).foreach {
    //   case (dst, src) =>
    //     dst := src
    // }
    io.enqIncResults.zipWithIndex.foreach {
      case (dst, idx) =>
        dst := enq_ptr.io.incResults(idx)
    }
    io.deqIncResults.zipWithIndex.foreach {
      case (dst, idx) =>
        dst := deq_ptr.io.incResults(idx)
    }

    enq_ptr.io.inc := io.enqueuePorts.zipWithIndex.map {
      case (enqPort, idx) =>
        // connect
        enqPort <> storeIns(enq_ptr.io.incResults(idx)(channelNumWidth - 1, 0))
        // return
        enqPort.valid && storeIns(enq_ptr.io.incResults(idx)(channelNumWidth - 1, 0)).ready
    }.map(_.asUInt).reduce(_ +& _)

    deq_ptr.io.inc := io.dequeuePorts.zipWithIndex.map {
      case (deqPort, idx) =>
        // connect
        deqPort <> storeOuts(deq_ptr.io.incResults(idx)(channelNumWidth - 1, 0))
        // return
        deqPort.valid && storeOuts(deq_ptr.io.incResults(idx)(channelNumWidth - 1, 0)).ready
    }.map(_.asUInt).reduce(_ +& _)
  }
}
