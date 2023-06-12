package pipeline.common

import chisel3._
import chisel3.util._
import spec._
import utils.MultiCounter

class MultiQueue[ElemT <: Data](
  queueLength:        Int,
  enqMaxNum:          Int,
  deqMaxNum:          Int,
  elemNdFactory:      => ElemT,
  blankElem:          => ElemT,
  needValidPorts:     Boolean = false,
  isRelativePosition: Boolean = false,
  writeFirst:         Boolean = true)
    extends Module {

  val io = IO(new Bundle {
    val isFlush      = Input(Bool())
    val enqueuePorts = Vec(enqMaxNum, Flipped(Decoupled(elemNdFactory)))
    val dequeuePorts = Vec(deqMaxNum, Decoupled(elemNdFactory))

    // deq_ptr -> enq_ptr
    val setPorts      = Input(Vec(queueLength, ValidIO(elemNdFactory)))
    val elems         = Output(Vec(queueLength, elemNdFactory))
    val emptyNum      = Output(UInt(log2Ceil(queueLength).W))
    val enqIncResults = Output(Vec(queueLength + 1, UInt(log2Ceil(queueLength + 1).W)))
    val enq_ptr       = Output(UInt(log2Ceil(queueLength + 1).W))
    val deq_ptr       = Output(UInt(log2Ceil(queueLength + 1).W))
    val elemValids    = if (needValidPorts) Some(Output(Vec(queueLength, Bool()))) else None
  })

  require(queueLength > enqMaxNum)
  require(queueLength > deqMaxNum)

  val ram       = RegInit(VecInit(Seq.fill(queueLength)(blankElem)))
  val ramValids = RegInit(VecInit(Seq.fill(queueLength)(false.B)))

  val enq_ptr = Module(new MultiCounter(queueLength, enqMaxNum))
  val deq_ptr = Module(new MultiCounter(queueLength, deqMaxNum))

  enq_ptr.io.inc   := 0.U
  enq_ptr.io.flush := io.isFlush
  deq_ptr.io.inc   := 0.U
  deq_ptr.io.flush := io.isFlush
  io.enqIncResults.zip(enq_ptr.io.incResults).foreach {
    case (dst, src) =>
      dst := src
  }
  io.enq_ptr := enq_ptr.io.value
  io.deq_ptr := deq_ptr.io.value

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
  io.emptyNum := emptyNum

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
    io.setPorts.zipWithIndex.foreach {
      case (s, idx) =>
        when(s.valid) {
          if (isRelativePosition) {
            ram(deq_ptr.io.incResults(idx)) := s.bits
          } else {
            ram(idx) := s.bits
          }
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
      when(en) {
        ram(enq_ptr.io.incResults(idx))       := enqPort.bits
        ramValids(enq_ptr.io.incResults(idx)) := true.B
      }
  }

  deq_ptr.io.inc := dequeueNum
  io.dequeuePorts.lazyZip(deqEn).zipWithIndex.foreach {
    case ((deq, en), idx) =>
      deq.bits := ram(deq_ptr.io.incResults(idx))
      when(en) {
        ramValids(deq_ptr.io.incResults(idx)) := false.B
      }

  }

  if (writeFirst) {
    io.setPorts.zipWithIndex.foreach {
      case (s, idx) =>
        when(s.valid) {
          if (isRelativePosition) {
            ram(deq_ptr.io.incResults(idx)) := s.bits
          } else {
            ram(idx) := s.bits
          }
        }
    }
  }

  io.elems.zipWithIndex.foreach {
    case (dst, idx) =>
      if (isRelativePosition) {
        dst := ram(deq_ptr.io.incResults(idx))
      } else {
        dst := ram(idx)
      }

  }

  io.elemValids match {
    case Some(elemValids) =>
      io.elemValids.get.zipWithIndex.foreach {
        case (dst, idx) =>
          if (isRelativePosition) {
            dst := ramValids(deq_ptr.io.incResults(idx))
          } else {
            dst := ramValids(idx)
          }
      }
    case None =>
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
