package pipeline.queue

import chisel3._
import chisel3.util._
import control.enums.ExceptionPos
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.DistributedQueue
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.queue.bundles.DecodeOutNdPort
import pipeline.queue.decode._
import spec._
import common.bundles.BackendRedirectPcNdPort
import pipeline.commit.bundles.PcInstBundle

class InstQueueEnqNdPort extends Bundle {
  val enqInfos = Vec(Param.fetchInstMaxNum, Valid(new FetchInstInfoBundle))
}
object InstQueueEnqNdPort {
  def default = 0.U.asTypeOf(new InstQueueEnqNdPort)
}

class FetchInstDecodeNdPort extends Bundle {
  val decode    = new DecodeOutNdPort
  val instInfo  = new InstInfoNdPort
  val fetchInfo = new PcInstBundle
}

object FetchInstDecodeNdPort {
  def default = 0.U.asTypeOf(new FetchInstDecodeNdPort)
}

// assert: enqueuePorts总是最低的几位有效
class MultiInstQueue(
  val queueLength: Int = Param.instQueueLength,
  val channelNum:  Int = Param.instQueueChannelNum,
  val fetchNum:    Int = Param.fetchInstMaxNum,
  val issueNum:    Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    val isFrontendFlush = Input(Bool())
    val isBackendFlush  = Input(Bool())

    val enqueuePort = Flipped(Decoupled(new InstQueueEnqNdPort))

    // `InstQueue` -> `IssueStage`
    val dequeuePorts = Vec(
      issueNum,
      Decoupled(new FetchInstDecodeNdPort)
    )

    val idleBlocking = Input(Bool())
    val hasInterrupt = Input(Bool())

    val redirectRequest = Output(new BackendRedirectPcNdPort)

    val pmu_instqueueFullValid = if (Param.usePmu) Some(Output(Bool())) else None
    val pmu_instqueueEmpty     = if (Param.usePmu) Some(Output(Bool())) else None
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
      new FetchInstInfoBundle,
      flow = (Param.instQueueCombineSel == false)
    )
  )

  val isIdle = RegInit(false.B)
  when(io.hasInterrupt) {
    isIdle := false.B
  }.elsewhen(io.idleBlocking) {
    isIdle := true.B
  }

  // Fallback
  instQueue.io.enqueuePorts.zipWithIndex.foreach {
    case (enq, idx) =>
      enq.valid := io.enqueuePort.bits.enqInfos(idx).valid && io.enqueuePort.ready && !isIdle && io.enqueuePort.valid
      enq.bits  := io.enqueuePort.bits.enqInfos(idx).bits
  }
  io.enqueuePort.ready := instQueue.io.enqueuePorts.map(_.ready).reduce(_ && _) && !isIdle
  instQueue.io.isFlush := io.isFrontendFlush

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
    new DistributedQueue(
      issueNum,
      issueNum,
      issueNum,
      2,
      new FetchInstDecodeNdPort
    )
  )
  resultQueue.io.isFlush := io.isBackendFlush

  val isBlockDequeueReg = RegInit(false.B)
  isBlockDequeueReg := Mux(
    io.isBackendFlush,
    false.B,
    Mux(
      io.isFrontendFlush,
      true.B,
      isBlockDequeueReg
    )
  )

  val redirectRequests = Wire(Vec(issueNum, new BackendRedirectPcNdPort))
  io.redirectRequest := PriorityMux(redirectRequests.map(_.en), redirectRequests)

  resultQueue.io.enqueuePorts.zip(instQueue.io.dequeuePorts).zipWithIndex.foreach {
    case ((dst, src), idx) =>
      dst.valid := src.valid
      src.ready := dst.ready
      when(isBlockDequeueReg || io.isFrontendFlush || redirectRequests.map(_.en).take(idx).foldLeft(false.B)(_ || _)) {
        dst.valid := false.B
        src.ready := false.B
      }
  }

  io.dequeuePorts.zip(resultQueue.io.dequeuePorts).foreach {
    case (dst, src) =>
      dst <> src
  }

  resultQueue.io.enqueuePorts
    .lazyZip(selectedDecoders)
    .lazyZip(decodeInstInfos)
    .lazyZip(redirectRequests)
    .zipWithIndex
    .foreach {
      case ((dequeuePort, selectedDecoder, decodeInstInfo, redirectRequest), index) =>
        dequeuePort.bits.instInfo         := InstInfoNdPort.default
        dequeuePort.bits.fetchInfo.pcAddr := decodeInstInfo.pcAddr
        dequeuePort.bits.fetchInfo.inst   := decodeInstInfo.inst

        val isMatched = WireDefault(decoderWires(index).map(_.isMatched).reduce(_ || _))
        dequeuePort.bits.instInfo.isValid                  := true.B
        dequeuePort.bits.instInfo.isCsrWrite               := selectedDecoder.info.csrWriteEn
        dequeuePort.bits.instInfo.exeOp                    := selectedDecoder.info.exeOp
        dequeuePort.bits.instInfo.isTlb                    := selectedDecoder.info.isTlb
        dequeuePort.bits.instInfo.needRefetch              := selectedDecoder.info.needRefetch
        dequeuePort.bits.instInfo.ftqInfo                  := decodeInstInfo.ftqInfo
        dequeuePort.bits.instInfo.ftqCommitInfo.isBranch   := selectedDecoder.info.isBranch
        dequeuePort.bits.instInfo.ftqCommitInfo.branchType := selectedDecoder.info.branchType

        dequeuePort.bits.instInfo.forbidParallelCommit := selectedDecoder.info.needRefetch

        dequeuePort.bits.instInfo.exceptionPos    := ExceptionPos.none
        dequeuePort.bits.instInfo.exceptionRecord := DontCare
        when(io.hasInterrupt) {
          dequeuePort.bits.instInfo.exceptionPos    := ExceptionPos.frontend
          dequeuePort.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.int
        }.elsewhen(decodeInstInfo.exceptionValid) {
          dequeuePort.bits.instInfo.exceptionPos    := ExceptionPos.frontend
          dequeuePort.bits.instInfo.exceptionRecord := decodeInstInfo.exception
        }.elsewhen(!isMatched) {
          dequeuePort.bits.instInfo.exceptionPos    := ExceptionPos.frontend
          dequeuePort.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.ine
        }

        dequeuePort.bits.decode := selectedDecoder
        when(!isMatched) {
          dequeuePort.bits.decode.info.issueEn.zipWithIndex.foreach {
            case (en, idx) =>
              en := (idx != Param.loadStoreIssuePipelineIndex).B
          }
        }

        redirectRequest.en := !selectedDecoder.info.isBranch && decodeInstInfo.ftqInfo.predictBranch && dequeuePort.valid && dequeuePort.ready
        redirectRequest.pcAddr := decodeInstInfo.pcAddr + 4.U
        redirectRequest.ftqId  := decodeInstInfo.ftqInfo.ftqId

        dequeuePort.bits.instInfo.ftqCommitInfo.isRedirect := redirectRequest.en

    }

  io.pmu_instqueueFullValid match {
    case Some(v) =>
      v := !io.enqueuePort.ready && !isIdle && !io.isFrontendFlush && !isBlockDequeueReg
    case None =>
  }

  io.pmu_instqueueEmpty match {
    case Some(v) =>
      v := !instQueue.io.dequeuePorts.head.valid
    case None =>
  }
}
