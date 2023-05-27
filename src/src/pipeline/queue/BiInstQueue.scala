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
// 尝试写双发射的queue，未接入，不用管它
// assert: enqueuePorts总是最低的几位有效
class BiInstQueue(
  val queueLength: Int = Param.instQueueLength,
  val issueNum:    Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    // val isFlush     = Input(Bool())
    val isFlush = Input(Bool())
    val enqueuePorts        = Vec(issueNum, Flipped(Decoupled(new InstInfoBundle)))

    // `InstQueue` -> `IssueStage`
    val dequeuePorts = Vec(
      issueNum,
      Decoupled(new Bundle {
        val decode   = new DecodeOutNdPort
        val instInfo = new InstInfoNdPort
      })
    )

    // val debugPort = Output(Vec(issueNum, new InstInfoBundle))
  })
  require(issueNum == 2)
//   val queue =
//     Queue(io.enqueuePorts(0), entries = queueLength, pipe = false, flow = true, flush = Some(io.pipelineControlPort.flush))

//   io.dequeuePort <> queue

  val ram     = RegInit(VecInit(Seq.fill(queueLength)(InstInfoBundle.default)))
  val enq_ptr = Module(new BiCounter(queueLength))
  val deq_ptr = Module(new BiCounter(queueLength))

  enq_ptr.io.inc   := 0.U
  enq_ptr.io.flush := io.isFlush
  deq_ptr.io.inc   := 0.U
  deq_ptr.io.flush := io.isFlush

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
  // val numWidth: Int = log2Ceil(issueNum)

  val enqEn = (io.enqueuePorts.map(port => (port.ready && port.valid)))
  // val enqueueNum = io.enqueuePorts.map(_.valid).map(_.asUInt).reduce(_ + _)
  // enqEn(0) + enqEn(1)
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

  // Decode

  val decodeInstInfos = WireDefault(VecInit(ram(deq_ptr.io.value), ram(deq_ptr.io.value + 1.U)))

  // Select a decoder

  val decoders0 = Seq(
    Module(new Decoder_2RI12),
    Module(new Decoder_2RI14),
    Module(new Decoder_2RI16),
    Module(new Decoder_2R),
    Module(new Decoder_3R),
    // Module(new Decoder_4R),
    Module(new Decoder_special)
  )

  val decoders1 = Seq(
    Module(new Decoder_2RI12),
    Module(new Decoder_2RI14),
    Module(new Decoder_2RI16),
    Module(new Decoder_2R),
    Module(new Decoder_3R),
    // Module(new Decoder_4R),
    Module(new Decoder_special)
  )

  decoders0.foreach(_.io.instInfoPort := decodeInstInfos(0))
  decoders1.foreach(_.io.instInfoPort := decodeInstInfos(1))

  val decoderWires = Wire(Vec(2, Vec(decoders0.length, new DecodeOutNdPort)))
  decoderWires.zip(Seq(decoders0, decoders1)).foreach {
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
      dequeuePort.bits.decode := selectedDecoder
      // InstInfoNdPort.setDefault(dequeuePort.bits.instInfo)
      dequeuePort.bits.instInfo      := InstInfoNdPort.default
      dequeuePort.bits.instInfo.pc   := decodeInstInfo.pcAddr
      dequeuePort.bits.instInfo.inst := decodeInstInfo.inst
      val isMatched = WireDefault(decoderWires(index).map(_.isMatched).reduce(_ || _))
      dequeuePort.bits.instInfo
        .exceptionRecords(Csr.ExceptionIndex.ine) := !isMatched
      dequeuePort.bits.instInfo.isValid := decodeInstInfo.pcAddr.orR  // TODO: Check if it can change to isMatched (see whether commit or not)
  }

  when(io.isFlush) {
    ram.foreach(_ := InstInfoBundle.default)
  }
}
