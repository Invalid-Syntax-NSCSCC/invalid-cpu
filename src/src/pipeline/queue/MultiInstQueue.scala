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
import pipeline.common.MultiQueue

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
  require(queueLength > fetchNum)
  require(queueLength > issueNum)

  val instQueue = Module(new MultiQueue(queueLength, fetchNum, issueNum, new InstInfoBundle, InstInfoBundle.default))

  // fall back
  instQueue.io.enqueuePorts <> io.enqueuePorts
  instQueue.io.isFlush      := io.isFlush
  instQueue.io.setPorts.zip(instQueue.io.elems).foreach {
    case (dst, src) =>
      dst.valid := false.B
      dst.bits  := src
  }
  instQueue.io.dequeuePorts.zip(io.dequeuePorts).foreach {
    case (q, out) =>
      q.ready   := out.ready
      out.valid := q.valid
  }

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

  io.dequeuePorts.lazyZip(selectedDecoders).lazyZip(decodeInstInfos).zipWithIndex.foreach {
    case ((dequeuePort, selectedDecoder, decodeInstInfo), index) =>
      dequeuePort.bits.decode        := selectedDecoder
      dequeuePort.bits.instInfo      := InstInfoNdPort.default
      dequeuePort.bits.instInfo.pc   := decodeInstInfo.pcAddr
      dequeuePort.bits.instInfo.inst := decodeInstInfo.inst
      val isMatched = WireDefault(decoderWires(index).map(_.isMatched).reduce(_ || _))
      dequeuePort.bits.instInfo.isValid := decodeInstInfo.pcAddr.orR // TODO: Check if it can change to isMatched (see whether commit or not)
      dequeuePort.bits.instInfo.csrWritePort.en   := selectedDecoder.info.csrWriteEn
      dequeuePort.bits.instInfo.csrWritePort.addr := selectedDecoder.info.csrAddr
      dequeuePort.bits.instInfo.exeOp             := selectedDecoder.info.exeOp
      dequeuePort.bits.instInfo.exeSel            := selectedDecoder.info.exeSel
      dequeuePort.bits.instInfo.tlbInfo           := selectedDecoder.info.tlbInfo
      dequeuePort.bits.instInfo.needCsr           := selectedDecoder.info.needCsr
      dequeuePort.bits.instInfo.exceptionRecords(
        Csr.ExceptionIndex.adef
      ) := decodeInstInfo.exceptionValid && decodeInstInfo.exception === Csr.ExceptionIndex.adef
      dequeuePort.bits.instInfo.exceptionRecords(
        Csr.ExceptionIndex.pif
      ) := decodeInstInfo.exceptionValid && decodeInstInfo.exception === Csr.ExceptionIndex.pif
      dequeuePort.bits.instInfo.exceptionRecords(
        Csr.ExceptionIndex.ppi
      ) := decodeInstInfo.exceptionValid && decodeInstInfo.exception === Csr.ExceptionIndex.ppi
      dequeuePort.bits.instInfo.exceptionRecords(
        Csr.ExceptionIndex.tlbr
      ) := decodeInstInfo.exceptionValid && decodeInstInfo.exception === Csr.ExceptionIndex.tlbr
      dequeuePort.bits.instInfo.exceptionRecords(Csr.ExceptionIndex.ine) := !isMatched
      dequeuePort.bits.instInfo.isExceptionValid                         := !isMatched || decodeInstInfo.exceptionValid
  }
}
