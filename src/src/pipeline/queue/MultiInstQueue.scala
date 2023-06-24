package pipeline.queue

import chisel3._
import chisel3.util._
import control.enums.ExceptionPos
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.DistributedQueue
import pipeline.dispatch.bundles.InstInfoBundle
import pipeline.queue.bundles.DecodeOutNdPort
import pipeline.queue.decode._
import spec._

// assert: enqueuePorts总是最低的几位有效
class MultiInstQueue(
  val queueLength: Int = Param.instQueueLength,
  val channelNum:  Int = Param.instQueueChannelNum,
  val fetchNum:    Int = Param.fetchInstMaxNum,
  val issueNum:    Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    val isFlush      = Input(Bool())
    val enqueuePorts = Vec(issueNum, Flipped(Decoupled(new InstInfoBundle)))

    // `InstQueue` -> `IssueStage`
    val dequeuePorts = Vec(
      issueNum,
      Decoupled(new FetchInstDecodeNdPort)
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
      new InstInfoBundle,
      InstInfoBundle.default
    )
  )

  // fall back
  instQueue.io.enqueuePorts.zip(io.enqueuePorts).foreach {
    case (dst, src) =>
      dst <> src
  }
  instQueue.io.isFlush := io.isFlush

  // Decode
  val decodeInstInfos = WireDefault(VecInit(instQueue.io.dequeuePorts.map(_.bits)))

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

  val resultQueue = Module(
    new MultiQueue(
      issueNum * 2,
      issueNum,
      issueNum,
      new FetchInstDecodeNdPort,
      FetchInstDecodeNdPort.default
    )
  )
  resultQueue.io.isFlush := io.isFlush
  resultQueue.io.setPorts.foreach { set =>
    set.valid := false.B
    set.bits  := DontCare
  }

  resultQueue.io.enqueuePorts.zip(instQueue.io.dequeuePorts).foreach {
    case (dst, src) =>
      dst.valid := src.valid
      src.ready := dst.ready
  }

  io.dequeuePorts.zip(resultQueue.io.dequeuePorts).foreach {
    case (dst, src) =>
      dst <> src
  }

  resultQueue.io.enqueuePorts.lazyZip(selectedDecoders).lazyZip(decodeInstInfos).zipWithIndex.foreach {
    case ((dequeuePort, selectedDecoder, decodeInstInfo), index) =>
      dequeuePort.bits.decode        := selectedDecoder
      dequeuePort.bits.instInfo      := InstInfoNdPort.default
      dequeuePort.bits.instInfo.pc   := decodeInstInfo.pcAddr
      dequeuePort.bits.instInfo.inst := decodeInstInfo.inst
      val isMatched = WireDefault(decoderWires(index).map(_.isMatched).reduce(_ || _))
      dequeuePort.bits.instInfo.isValid := decodeInstInfo.pcAddr =/= 0.U // TODO: Check if it can change to isMatched (see whether commit or not)
      dequeuePort.bits.instInfo.csrWritePort.en   := selectedDecoder.info.csrWriteEn
      dequeuePort.bits.instInfo.csrWritePort.addr := selectedDecoder.info.csrAddr
      dequeuePort.bits.instInfo.exeOp             := selectedDecoder.info.exeOp
      dequeuePort.bits.instInfo.exeSel            := selectedDecoder.info.exeSel
      dequeuePort.bits.instInfo.tlbInfo           := selectedDecoder.info.tlbInfo
      dequeuePort.bits.instInfo.needCsr           := selectedDecoder.info.needCsr

      dequeuePort.bits.instInfo.exceptionPos    := ExceptionPos.none
      dequeuePort.bits.instInfo.exceptionRecord := decodeInstInfo.exception
      when(decodeInstInfo.exceptionValid) {
        dequeuePort.bits.instInfo.exceptionPos := ExceptionPos.frontend
      }.elsewhen(!isMatched) {
        dequeuePort.bits.instInfo.exceptionPos    := ExceptionPos.frontend
        dequeuePort.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.ine
      }
  }
}
