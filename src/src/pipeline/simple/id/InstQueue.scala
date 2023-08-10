package pipeline.simple.id

import chisel3._
import chisel3.util._
import common.DistributedQueue
import common.bundles.BackendRedirectPcNdPort
import control.enums.ExceptionPos
import pipeline.common.bundles.{FetchInstInfoBundle, InstQueueEnqNdPort, PcInstBundle}
import pipeline.simple.bundles.InstInfoNdPort
import pipeline.simple.bundles.RobRequestPort
import pipeline.simple.decode._
import pipeline.simple.decode.bundles._
import spec._

class FetchInstDecodeNdPort extends Bundle {
  val decode   = new DecodeOutNdPort
  val instInfo = new InstInfoNdPort
  // val fetchInfo = new PcInstBundle
}

object FetchInstDecodeNdPort {
  def default = 0.U.asTypeOf(new FetchInstDecodeNdPort)
}

// assert: enqueuePorts总是最低的几位有效
class InstQueue(
  queueLength: Int = Param.instQueueLength,
  channelNum:  Int = Param.instQueueChannelNum,
  fetchNum:    Int = Param.fetchInstMaxNum,
  issueNum:    Int = Param.issueInstInfoMaxNum)
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

    val robIdRequests = Vec(issueNum, Flipped(new RobRequestPort))

    val plv = Input(UInt(2.W))

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
      flow = !Param.instQueueCombineSel
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

  val decoderMatrix = Seq.fill(issueNum)(
    Seq(
      Module(new Decoder_2RI12),
      Module(new Decoder_2RI14),
      Module(new Decoder_2RI16),
      Module(new Decoder_2R),
      Module(new Decoder_3R),
      Module(new Decoder_special)
    )
  )

  decoderMatrix.zip(decodeInstInfos).foreach {
    case (decoders, decodeInstInfo) =>
      decoders.foreach(_.io.instInfoPort := decodeInstInfo)
  }

  val decoderWires = Wire(Vec(issueNum, Vec(decoderMatrix.head.length, new DecodeOutNdPort)))
  decoderWires.zip(decoderMatrix).foreach {
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

  // rob id request

  val redirectRequests = Wire(Vec(issueNum, new BackendRedirectPcNdPort))
  io.redirectRequest := DontCare // PriorityMux(redirectRequests.map(_.en), redirectRequests)

  resultQueue.io.enqueuePorts.lazyZip(instQueue.io.dequeuePorts).lazyZip(io.robIdRequests).zipWithIndex.foreach {
    case ((dst, src, robIdReq), idx) =>
      dst.valid := src.valid
      src.ready := dst.ready
      when(
        isBlockDequeueReg ||
          io.isFrontendFlush ||
          !robIdReq.result.valid
      ) {
        dst.valid := false.B
        src.ready := false.B
      }

      robIdReq.request.valid       := src.valid && src.ready
      robIdReq.request.bits.pcAddr := src.bits.pcAddr
      robIdReq.request.bits.inst   := src.bits.inst
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
      case (
            (
              dequeuePort,
              selectedDecoder,
              decodeInstInfo,
              redirectRequest
            ),
            index
          ) =>
        val robIdReq = io.robIdRequests(index)
        dequeuePort.bits.instInfo := InstInfoNdPort.default
        // dequeuePort.bits.fetchInfo.pcAddr := decodeInstInfo.pcAddr
        // dequeuePort.bits.fetchInfo.inst   := decodeInstInfo.inst

        val isMatched = WireDefault(decoderWires(index).map(_.isMatched).reduce(_ || _))
        dequeuePort.bits.instInfo.isValid     := true.B
        dequeuePort.bits.instInfo.isCsrWrite  := selectedDecoder.info.csrWriteEn
        dequeuePort.bits.instInfo.exeOp       := selectedDecoder.info.exeOp
        dequeuePort.bits.instInfo.isTlb       := selectedDecoder.info.isTlb
        dequeuePort.bits.instInfo.needRefetch := selectedDecoder.info.needRefetch
        dequeuePort.bits.instInfo.ftqInfo     := decodeInstInfo.ftqInfo
        dequeuePort.bits.instInfo.ftqCommitInfo.branchType.foreach(_ := selectedDecoder.info.branchType)
        dequeuePort.bits.instInfo.ftqCommitInfo.isBranch.foreach(_ := selectedDecoder.info.isBranch)

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
        }.elsewhen(
          io.plv === 3.U &&
            selectedDecoder.info.isPrivilege
        ) {
          dequeuePort.bits.instInfo.exceptionPos    := ExceptionPos.frontend
          dequeuePort.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.ipe
        }

        dequeuePort.bits.decode := selectedDecoder

        redirectRequest.en := !selectedDecoder.info.isBranch && decodeInstInfo.ftqInfo.predictBranch && dequeuePort.valid && dequeuePort.ready
        redirectRequest.pcAddr := decodeInstInfo.pcAddr + 4.U
        redirectRequest.ftqId  := decodeInstInfo.ftqInfo.ftqId

        dequeuePort.bits.instInfo.ftqCommitInfo.isRedirect := redirectRequest.en
        dequeuePort.bits.instInfo.robId                    := robIdReq.result.bits

        if (Param.isDiffTest) {
          dequeuePort.bits.instInfo.pc.get   := decodeInstInfo.pcAddr
          dequeuePort.bits.instInfo.inst.get := decodeInstInfo.inst
        }

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