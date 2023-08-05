package pipeline.simple

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
import frontend.bundles.QueryPcBundle
import pipeline.simple.bundles.MainExeBranchInfoBundle

// class FetchInstDecodeNdPort extends Bundle {
//   val decode   = new DecodeOutNdPort
//   val instInfo = new InstInfoNdPort
// }

// object FetchInstDecodeNdPort {
//   def default = 0.U.asTypeOf(new FetchInstDecodeNdPort)
// }

// assert: enqueuePorts总是最低的几位有效
class DispatchInstQueue(
  queueLength: Int = Param.instQueueLength,
  channelNum:  Int = Param.instQueueChannelNum,
  fetchNum:    Int = Param.fetchInstMaxNum,
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Module {
  val io = IO(new Bundle {
    val isFrontendFlush = Input(Bool())
    val isBackendFlush  = Input(Bool())

    val enqueuePort = Flipped(Decoupled(new InstQueueEnqNdPort))

    // `InstQueue` -> `IssueStage`
    val dequeuePort = Decoupled(new RegReadNdPort)

    val idleBlocking = Input(Bool())
    val hasInterrupt = Input(Bool())

    val robIdRequests = Vec(issueNum, Flipped(new RobRequestPort))

    val queryPcPort = Flipped(new QueryPcBundle)

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

  val resultOut = WireDefault(0.U.asTypeOf(Decoupled(new RegReadNdPort)))

  val resultQueue = Queue(resultOut, entries = 2, pipe = false, flow = false, flush = Some(io.isBackendFlush))

  val isBlockDequeueReg = RegInit(false.B)
  when(io.isBackendFlush) {
    isBlockDequeueReg := false.B
  }.elsewhen(
    io.isFrontendFlush || (
      resultOut.valid &&
        resultOut.ready &&
        resultOut.bits.decodePorts.map { port =>
          port.valid && (
            port.bits.decode.info.needRefetch ||
              port.bits.instInfo.exceptionPos =/= ExceptionPos.none
          )
        }.reduce(_ || _)
    )
  ) {
    isBlockDequeueReg := true.B
  }

  io.dequeuePort <> resultQueue

  // rob id request

  resultOut.bits.decodePorts.lazyZip(instQueue.io.dequeuePorts).lazyZip(io.robIdRequests).zipWithIndex.foreach {
    case ((dst, src, robIdReq), idx) =>
      src.ready := resultOut.ready
      dst.valid := src.valid

      robIdReq.request.valid       := src.valid && src.ready
      robIdReq.request.bits.pcAddr := src.bits.pcAddr
      robIdReq.request.bits.inst   := src.bits.inst

      // block
      when(
        isBlockDequeueReg ||
          io.isFrontendFlush ||
          !robIdReq.result.valid
      ) {
        dst.valid := false.B
        src.ready := false.B
      }

      // data dependence
      resultOut.bits.decodePorts.take(idx).foreach { prev =>
        val prevGprWrite = prev.bits.decode.info.gprWritePort
        dst.bits.decode.info.gprReadPorts.foreach { r =>
          when(r.en && prevGprWrite.en && r.addr === prevGprWrite.addr) {
            dst.valid := false.B
            src.ready := false.B
          }
        }
      }

      // should issue in main
      if (idx != 0) {
        when(dst.bits.decode.info.isIssueMainPipeline) {
          dst.valid := false.B
          src.ready := false.B
        }
      }
  }

  resultOut.valid := resultOut.bits.decodePorts.map(_.valid).reduce(_ || _)

  resultOut.bits.mainExeBranchInfo.pc              := decodeInstInfos.head.pcAddr
  io.queryPcPort.ftqId                             := decodeInstInfos.head.ftqInfo.ftqId + 1.U
  resultOut.bits.mainExeBranchInfo.predictJumpAddr := io.queryPcPort.pc

  resultOut.bits.decodePorts
    .lazyZip(selectedDecoders)
    .lazyZip(decodeInstInfos)
    .zipWithIndex
    .foreach {
      case (
            (
              dequeuePort,
              selectedDecoder,
              decodeInstInfo
            ),
            index
          ) =>
        val robIdReq = io.robIdRequests(index)
        dequeuePort.bits.instInfo := InstInfoNdPort.default

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
        }.elsewhen(
          io.plv === 3.U &&
            selectedDecoder.info.isPrivilege
        ) {
          dequeuePort.bits.instInfo.exceptionPos    := ExceptionPos.frontend
          dequeuePort.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.ipe
        }

        dequeuePort.bits.decode := selectedDecoder

        dequeuePort.bits.instInfo.robId := robIdReq.result.bits

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
