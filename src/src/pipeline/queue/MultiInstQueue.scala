package pipeline.queue

import chisel3._
import chisel3.util._
import control.bundles.PipelineControlNdPort
import pipeline.dispatch.bundles.InstInfoBundle
import pipeline.queue.bundles.DecodeOutNdPort
import pipeline.queue.decode.{Decoder_2R, Decoder_2RI12, Decoder_2RI14, Decoder_2RI16, Decoder_3R, Decoder_special}
import pipeline.writeback.bundles.InstInfoNdPort
import spec._
import utils.BiCounter
import utils.MultiCounter

// assert: enqueuePorts总是最低的几位有效
class MultiInstQueue(
  val queueLength: Int = Param.instQueueLength,
  val fetchNum:    Int = Param.fetchInstMaxNum,
  val issueNum:    Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    // val isFlush     = Input(Bool())
    val isFlush      = Input(Bool())
    val enqueuePorts = Vec(issueNum, Flipped(Decoupled(new InstInfoBundle)))

    // `InstQueue` -> `IssueStage`
    val dequeuePorts = Vec(
      issueNum,
      Decoupled(new Bundle {
        val decode   = new DecodeOutNdPort
        val instInfo = new InstInfoNdPort
      })
    )
  })
  require(issueNum == 2)
  require(queueLength > fetchNum)
  require(queueLength > issueNum)

  val ram     = RegInit(VecInit(Seq.fill(queueLength)(InstInfoBundle.default)))
  val enq_ptr = Module(new MultiCounter(queueLength, fetchNum))
  val deq_ptr = Module(new MultiCounter(queueLength, issueNum))

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

  val isEmptyBy = WireDefault(VecInit(Seq.range(0, issueNum).map(_.U === storeNum)))
  val isFullBy  = WireDefault(VecInit(Seq.range(0, fetchNum).map(_.U === emptyNum)))

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

  // Decode
  val decodeInstInfos = WireDefault(VecInit(Seq.range(0, issueNum).map(idx => ram(deq_ptr.io.incResults(idx)))))

  // Select a decoder

  val decoderss = Seq.fill(issueNum)(
    Seq(
      Module(new Decoder_2RI12),
      Module(new Decoder_2RI14),
      Module(new Decoder_2RI16),
      Module(new Decoder_2R),
      Module(new Decoder_3R),
      // Module(new Decoder_4R),
      Module(new Decoder_special)
    )
  )

  decoderss.zip(decodeInstInfos).foreach {
    case (decoders, decodeInstInfo) =>
      decoders.foreach(_.io.instInfoPort := decodeInstInfo)
  }

  val decoderWires = Wire(Vec(issueNum, Vec(decoderss(0).length, new DecodeOutNdPort)))
  decoderWires.zip(decoderss).foreach {
    case (decoderWire, decoders) =>
      decoderWire.zip(decoders).foreach {
        case (port, decoder) =>
          port := decoder.io.out
      }
  }

  val decoderIndices = WireDefault(VecInit(decoderWires.map { decoderWire =>
    OHToUInt(Cat(decoderWire.map(_.isMatched).reverse))
  }))
  val selectedDecoders = WireDefault(VecInit(decoderWires.zip(decoderIndices).map {
    case (decoderWire, decoderIndex) =>
      decoderWire(decoderIndex)
  }))

  io.dequeuePorts.lazyZip(selectedDecoders).lazyZip(decodeInstInfos).zipWithIndex.foreach {
    case ((dequeuePort, selectedDecoder, decodeInstInfo), index) =>
      dequeuePort.bits.decode        := selectedDecoder
      dequeuePort.bits.instInfo      := InstInfoNdPort.default
      dequeuePort.bits.instInfo.pc   := decodeInstInfo.pcAddr
      dequeuePort.bits.instInfo.inst := decodeInstInfo.inst
      val isMatched = WireDefault(decoderWires(index).map(_.isMatched).reduce(_ || _))
      dequeuePort.bits.instInfo
        .exceptionRecords(Csr.ExceptionIndex.ine) := !isMatched
      dequeuePort.bits.instInfo.isExceptionValid  := !isMatched
      dequeuePort.bits.instInfo.isValid := decodeInstInfo.pcAddr.orR // TODO: Check if it can change to isMatched (see whether commit or not)
      dequeuePort.bits.instInfo.csrWritePort.en   := selectedDecoder.info.csrWriteEn
      dequeuePort.bits.instInfo.csrWritePort.addr := selectedDecoder.info.csrAddr
      dequeuePort.bits.instInfo.exeOp             := selectedDecoder.info.exeOp
      dequeuePort.bits.instInfo.exeSel            := selectedDecoder.info.exeSel
      dequeuePort.bits.instInfo.tlbInfo           := selectedDecoder.info.tlbInfo
      dequeuePort.bits.instInfo.needCsr           := selectedDecoder.info.needCsr
  }

  when(io.isFlush) {
    ram.foreach(_ := InstInfoBundle.default)
    maybeFull := false.B
    io.dequeuePorts.foreach(_.valid := false.B)
    storeNum := 0.U
    isEmpty  := true.B
    isFull   := false.B
  }
}
