package pipeline.common

import chisel3._
import chisel3.util._
import spec._
import utils.MultiCounter

class MultiQueue[ElemT <: Data](
  queueLength:   Int,
  enqMaxNum:     Int,
  deqMaxNum:     Int,
  elemNdFactory: => ElemT,
  blankElem:     => ElemT,
  writeFirst:    Boolean = true)
    extends Module {

  val io = IO(new Bundle {
    val isFlush      = Input(Bool())
    val enqueuePorts = Vec(enqMaxNum, Flipped(Decoupled(elemNdFactory)))
    val dequeuePorts = Vec(deqMaxNum, Decoupled(elemNdFactory))

    val setPorts = Input(Vec(queueLength, ValidIO(elemNdFactory)))
    val elems    = Output(Vec(queueLength, elemNdFactory))
  })

  require(queueLength > enqMaxNum)
  require(queueLength > deqMaxNum)

  val ram = RegInit(VecInit(Seq.fill(queueLength)(blankElem)))
  io.elems.zip(ram).foreach {
    case (dst, src) =>
      dst := src
  }
  val enq_ptr = Module(new MultiCounter(queueLength, enqMaxNum))
  val deq_ptr = Module(new MultiCounter(queueLength, deqMaxNum))

  enq_ptr.io.inc   := 0.U
  enq_ptr.io.flush := io.isFlush
  deq_ptr.io.inc   := 0.U
  deq_ptr.io.flush := io.isFlush

  val maybeFull = RegInit(false.B)
  val ptrMatch  = enq_ptr.io.value === deq_ptr.io.value
  val isEmpty   = WireDefault(ptrMatch && !maybeFull)
  val isFull    = WireDefault(ptrMatch && maybeFull)

  val storeNum = WireDefault(
    Mux(
      enq_ptr.io.value === deq_ptr.io.value,
      Mux(isEmpty, 0.U, queueLength.U),
      Mux(
        enq_ptr.io.value > deq_ptr.io.value,
        enq_ptr.io.value - deq_ptr.io.value,
        (queueLength.U - deq_ptr.io.value) + enq_ptr.io.value
      )
    )
  )
  val emptyNum = WireDefault(queueLength.U - storeNum)

  val isEmptyBy = WireDefault(VecInit(Seq.range(0, deqMaxNum).map(_.U === storeNum)))
  val isFullBy  = WireDefault(VecInit(Seq.range(0, enqMaxNum).map(_.U === emptyNum)))

  io.enqueuePorts.zipWithIndex.foreach {
    case (enq, idx) =>
      enq.ready := !isFullBy.take(idx + 1).reduce(_ || _)
  }

  io.dequeuePorts.zipWithIndex.foreach {
    case (deq, idx) =>
      deq.valid := !isEmptyBy.take(idx + 1).reduce(_ || _)
  }

  // enqueue
  val enqEn      = io.enqueuePorts.map(port => (port.ready && port.valid))
  val enqueueNum = enqEn.map(_.asUInt).reduce(_ +& _)

  // dequeue
  val deqEn      = io.dequeuePorts.map(port => (port.ready && port.valid))
  val dequeueNum = deqEn.map(_.asUInt).reduce(_ +& _)

  if (!writeFirst) {
    ram.zip(io.setPorts).foreach {
      case (r, s) =>
        when(s.valid) {
          r := s.bits
        }
    }
  }

  when(enqueueNum > dequeueNum) {
    maybeFull := true.B
  }.elsewhen(enqueueNum < dequeueNum) {
    maybeFull := false.B
  }

  enq_ptr.io.inc := enqueueNum
  io.enqueuePorts.lazyZip(enqEn).zipWithIndex.foreach {
    case ((enqPort, en), idx) =>
      when(idx.U < enqueueNum) {
        ram(enq_ptr.io.incResults(idx)) := enqPort.bits
      }
  }

  deq_ptr.io.inc := dequeueNum
  io.dequeuePorts.zipWithIndex.foreach {
    case (deq, idx) =>
      deq.bits := ram(deq_ptr.io.incResults(idx))
  }

  if (writeFirst) {
    ram.zip(io.setPorts).foreach {
      case (r, s) =>
        when(s.valid) {
          r := s.bits
        }
    }
  }

  when(io.isFlush) {
    ram.foreach(_ := blankElem)
    maybeFull := false.B
    io.dequeuePorts.foreach(_.valid := false.B)
    storeNum := 0.U
    isEmpty  := true.B
    isFull   := false.B
  }

}
