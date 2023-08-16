package pipeline.simple.id

import chisel3._
import chisel3.util._
import common.DistributedQueue
import control.enums.ExceptionPos
import pipeline.common.bundles.FetchInstInfoBundle
import pipeline.simple.bundles.{InstInfoNdPort, RobRequestPort}
import spec._

class DecodeStage(issueNum: Int = Param.issueInstInfoMaxNum) extends Module {
  val io = IO(new Bundle {

    val isFrontendFlush = Input(Bool())
    val isBackendFlush  = Input(Bool())

    val ins = Vec(
      issueNum,
      Flipped(Decoupled(new FetchInstInfoBundle))
    )

    val outs = Vec(
      issueNum,
      Decoupled(new RegReadNdPort)
    )

    val robIdRequests = Vec(issueNum, Flipped(new RobRequestPort))

    val idleBlocking = Input(Bool())
    val hasInterrupt = Input(Bool())
    val plv          = Input(UInt(2.W))

  })

  private val queueLength = 4

  // Decode
  val decodeInstInfos = VecInit(io.ins.map(_.bits))

  val decoders = Seq.fill(issueNum)(Module(new DecodeUnit))

  decoders.zip(decodeInstInfos).foreach {
    case (decoder, decodeInstInfo) =>
      decoder.io.in := decodeInstInfo
  }

  val selectedDecoders = decoders.map(_.io.out)

  val resultQueue = Module(
    new DistributedQueue(
      issueNum,
      issueNum,
      issueNum,
      queueLength / issueNum,
      new RegReadNdPort
    )
  )
  resultQueue.io.isFlush := io.isBackendFlush

  io.outs.zip(resultQueue.io.dequeuePorts).foreach {
    case (dst, src) =>
      dst <> src
  }

  val isIdle = RegInit(false.B)
  when(io.hasInterrupt) {
    isIdle := false.B
  }.elsewhen(io.idleBlocking) {
    isIdle := true.B
  }

  val refetchOrExcps = resultQueue.io.enqueuePorts.map { port =>
    port.valid && port.ready && (
      port.bits.instInfo.needRefetch ||
        port.bits.instInfo.exceptionPos =/= ExceptionPos.none
    )
  }

  val isBlockDequeueReg = RegInit(false.B)
  when(io.isBackendFlush) {
    isBlockDequeueReg := false.B
  }.elsewhen(
    io.isFrontendFlush
      || (
        refetchOrExcps.reduce(_ || _)
      )
  ) {
    isBlockDequeueReg := true.B
  }

  val isBlock = WireDefault(isIdle || isBlockDequeueReg)

  io.ins.lazyZip(selectedDecoders).lazyZip(resultQueue.io.enqueuePorts).zipWithIndex.foreach {
    case ((in, decodeRes, enq), index) =>
      val robIdReq = io.robIdRequests(index)
      val inBits   = in.bits

      robIdReq.request.valid       := in.ready && in.valid
      robIdReq.request.bits.inst   := inBits.inst
      robIdReq.request.bits.pcAddr := inBits.pcAddr
      in.ready  := enq.ready && !isBlock && robIdReq.result.valid && !refetchOrExcps.take(index).fold(false.B)(_ || _)
      enq.valid := in.valid && !isBlock && robIdReq.result.valid && !refetchOrExcps.take(index).fold(false.B)(_ || _)

      val outInstInfo = enq.bits.instInfo

      outInstInfo := InstInfoNdPort.default
      // dequeuePort.bits.fetchInfo.pcAddr := decodeInstInfo.pcAddr
      // dequeuePort.bits.fetchInfo.inst   := decodeInstInfo.inst

      val isMatched = decodeRes.isMatched
      enq.bits.instInfo.isValid         := true.B
      outInstInfo.isCsrWrite            := decodeRes.info.csrWriteEn
      outInstInfo.exeOp                 := decodeRes.info.exeOp
      outInstInfo.isTlb                 := decodeRes.info.isTlb
      outInstInfo.needRefetch           := decodeRes.info.needRefetch
      outInstInfo.ftqInfo.isLastInBlock := inBits.ftqInfo.isLastInBlock
      outInstInfo.ftqInfo.ftqId         := inBits.ftqInfo.ftqId

      enq.bits.ftqPredictInfo.idxInBlock     := inBits.ftqInfo.idxInBlock
      enq.bits.ftqPredictInfo.predictBranch  := inBits.ftqInfo.predictBranch
      enq.bits.ftqPredictInfo.isPredictValid := inBits.ftqInfo.isPredictValid

      outInstInfo.ftqCommitInfo.branchType.foreach(_ := decodeRes.info.branchType)
      outInstInfo.ftqCommitInfo.isBranch.foreach(_ := decodeRes.info.isBranch)

      outInstInfo.forbidParallelCommit := decodeRes.info.isIssueMainPipeline

      outInstInfo.exceptionPos    := ExceptionPos.none
      outInstInfo.exceptionRecord := DontCare
      when(io.hasInterrupt) {
        outInstInfo.exceptionPos    := ExceptionPos.frontend
        outInstInfo.exceptionRecord := Csr.ExceptionIndex.int
      }.elsewhen(inBits.exceptionValid) {
        outInstInfo.exceptionPos    := ExceptionPos.frontend
        outInstInfo.exceptionRecord := inBits.exception
      }.elsewhen(!isMatched) {
        outInstInfo.exceptionPos    := ExceptionPos.frontend
        outInstInfo.exceptionRecord := Csr.ExceptionIndex.ine
      }.elsewhen(
        io.plv === 3.U &&
          decodeRes.info.isPrivilege
      ) {
        outInstInfo.exceptionPos    := ExceptionPos.frontend
        outInstInfo.exceptionRecord := Csr.ExceptionIndex.ipe
      }

      // enq.bits.gprReadPorts := decodeRes.info.gprReadPorts
      // enq.bits.gprWritePort := decodeRes.info.gprWritePort
      enq.bits.decode := decodeRes
      enq.bits.pc     := in.bits.pcAddr
      // enq.bits.isIssueMainPipeline := decodeRes.info.isIssueMainPipeline

      outInstInfo.robId := robIdReq.result.bits

      if (Param.isDiffTest) {
        outInstInfo.pc.get   := inBits.pcAddr
        outInstInfo.inst.get := inBits.inst
      }
  }
}
