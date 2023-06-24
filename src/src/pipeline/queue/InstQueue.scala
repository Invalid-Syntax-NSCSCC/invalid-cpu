package pipeline.queue

import chisel3._
import chisel3.util._
import control.bundles.PipelineControlNdPort
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.dispatch.bundles.InstInfoBundle
import pipeline.queue.bundles.DecodeOutNdPort
import pipeline.queue.decode._
import spec._

class InstQueue(val queueLength: Int = Param.instQueueLength) extends Module {
  val io = IO(new Bundle {
    // val isFlush     = Input(Bool())
    val pipelineControlPort = Input(new PipelineControlNdPort)
    val enqueuePort         = Flipped(Decoupled(new InstInfoBundle))

    // `InstQueue` -> `IssueStage`
    // val dequeuePort = Decoupled(new InstInfoBundle)
    val dequeuePort = Decoupled(new Bundle {
      val decode   = new DecodeOutNdPort
      val instInfo = new InstInfoNdPort
    })
  })

  // val queue =
  // Queue(io.enqueuePort, entries = queueLength, pipe = false, flow = true, flush = Some(io.pipelineControlPort.flush))

  val ram       = RegInit(VecInit(Seq.fill(queueLength)(InstInfoBundle.default)))
  val enq_ptr   = Counter(queueLength)
  val deq_ptr   = Counter(queueLength)
  val maybeFull = RegInit(false.B)
  val ptrMatch  = enq_ptr.value === deq_ptr.value
  val isEmpty   = ptrMatch && !maybeFull
  val isFull    = ptrMatch && maybeFull

  val enqueueRequest = WireDefault(io.enqueuePort.valid)
  val enqueueBits    = WireDefault(io.enqueuePort.bits)
  val dequeueRequest = WireDefault(io.dequeuePort.ready)

  when(enqueueRequest && !isFull) {
    ram(enq_ptr.value) := enqueueBits
    enq_ptr.inc()
  }
  when(dequeueRequest && !isEmpty) {
    deq_ptr.inc()
  }
  when(enqueueRequest =/= dequeueRequest) {
    // 入队就可能满，否则不可能满
    maybeFull := enqueueRequest
  }

  io.enqueuePort.ready := !isFull
  io.dequeuePort.valid := !isEmpty

  // Decode

  // Select a decoder
  val decodeInstInfo = WireDefault(ram(deq_ptr.value))

  val decoders = Seq(
    Module(new Decoder_2RI12),
    Module(new Decoder_2RI14),
    Module(new Decoder_2RI16),
    // Module(new Decoder_2R),
    Module(new Decoder_3R),
    // Module(new Decoder_4R),
    Module(new Decoder_special)
  )
  decoders.foreach(_.io.instInfoPort := decodeInstInfo)

  val decoderWires = Wire(Vec(decoders.length, new DecodeOutNdPort))
  decoderWires.zip(decoders).foreach {
    case (port, decoder) =>
      port := decoder.io.out
  }

  val isMatched       = WireDefault(decoderWires.map(_.isMatched).reduce(_ || _))
  val decoderIndex    = WireDefault(OHToUInt(Cat(decoderWires.map(_.isMatched).reverse)))
  val selectedDecoder = WireDefault(decoderWires(decoderIndex))

  io.dequeuePort.bits.decode := selectedDecoder
  // InstInfoNdPort.setDefault(io.dequeuePort.bits.instInfo)
  io.dequeuePort.bits.instInfo := InstInfoNdPort.default
  // io.dequeuePort.bits.instInfo.exceptionRecords(Csr.ExceptionIndex.ine) := !isMatched
  io.dequeuePort.bits.instInfo.pc   := decodeInstInfo.pcAddr
  io.dequeuePort.bits.instInfo.inst := decodeInstInfo.inst

  // io.dequeuePort <> queue

  when(io.pipelineControlPort.flush) {
    enq_ptr.reset()
    deq_ptr.reset()
    maybeFull := false.B
  }
}
