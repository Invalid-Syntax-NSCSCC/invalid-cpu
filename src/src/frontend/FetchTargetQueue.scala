package frontend

import spec._
import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import chisel3.experimental.Param
import frontend.bundles.{BpuFtqPort, CuCommitFtqPort, ExeFtqPort, FtqBlockPort, FtqBpuMetaPort}

class FetchTargetQueue(
  val queueSize: Int = Param.BPU.ftqSize,
  val issueNum:  Int = Param.issueInstInfoMaxNum)
    extends Module {
  // parameter
  val ptrWidth = log2Ceil(queueSize)

  val io = IO(new Bundle {
    // <-> Frontend flush control
    val backendFlush      = Input(Bool())
    val backendFlushFtqId = Input(UInt(ptrWidth.W))
    val instFetchFlush    = Input(Bool())
    val instFetchFtqId    = Input(UInt(ptrWidth.W))

    // <-> BPU
    val bpuFtqPort = new BpuFtqPort

    // <-> Backend
    // <-> Cu commit
    val cuCommitFtqPort = new CuCommitFtqPort
    // <-> Ex query port
    val exeFtqPort = new ExeFtqPort

    // <-> IFU
    val ifuPort             = Output(new FtqBlockPort)
    val ifuFrontendRedirect = Output(Bool())
    val ifuFtqId            = Output(UInt(ptrWidth.W))
    val ifuAccept           = Input(Bool()) // Must return in the same cycle
  })

  // Signals
  val queueFull                = WireDefault(false.B)
  val queueFullDelay           = RegInit(false.B)
  val ifuSendReq               = WireInit(false.B)
  val ifuSendReqDelay          = RegInit(false.B)
  val mainBpuRedirectDelay     = RegInit(false.B)
  val mainBpuRedirectModifyFtq = WireDefault(false.B)
  val ifuFrontendRedirect      = WireDefault(false.B)
  val ifuFrontendRedirectDelay = RegInit(false.B)

  val bpuPtr      = RegInit(0.U(ptrWidth.W))
  val ifuPtr      = RegInit(0.U(ptrWidth.W))
  val commPtr     = RegInit(0.U(ptrWidth.W))
  val bpuPtrPlus1 = WireDefault(0.U(ptrWidth.W))
  bpuPtrPlus1 := bpuPtr + 1.U

  // Queue data structure
  val ftqVec     = RegInit(Vec(queueSize, new FtqBlockPort))
  val ftqNextVec = Vec(queueSize, new FtqBlockPort)
  // FTQ meta
  val bpuMetaWriteValid = WireDefault(false.B)
  val bpuMetaWritePtr   = RegInit(0.U(ptrWidth.W))
  val bpuMetaWriteEntry = new FtqBpuMetaEntry
  val ftqBpuMetaVec     = Vec(queueSize, new FtqBpuMetaEntry)
  val ftqBranchMetaVec  = Vec(queueSize, new FtqBranchMetaEntry)

  val backendCommitNum = WireInit(0.U(log2Ceil(issueNum).W))
  backendCommitNum := io.cuCommitFtqPort.bitMask.map(_.asUInt).reduce(_ +& _)

  // IF sent rreq
  ifuSendReq               := ftqVec(ifuPtr).valid & io.ifuAccept
  mainBpuRedirectModifyFtq := io.bpuFtqPort.ftqP1.valid
  ifuFrontendRedirect      := (bpuPtr === (ifuPtr + 1.U)) & mainBpuRedirectModifyFtq & (ifuSendReq | ifuSendReqDelay)

  // queue full
  queueFull                := (bpuPtrPlus1 === commPtr)
  queueFullDelay           := (queueFull)
  ifuSendReqDelay          := (ifuSendReq)
  ifuFrontendRedirectDelay := (ifuFrontendRedirect)
  mainBpuRedirectDelay     := (io.bpuFtqPort.mainBpuRedirectValid)

  ftqVec := ftqNextVec

//  //TODO debug signal
//  val debugQueuePcVec = Vec(queueSize, spec.Width.Mem._addr)
//  for (i <- 0 to queueSize) {
//    debugQueuePcVec(i) := ftqVec(i).startPc
//  }

  // ptr
  io.ifuFtqId := ifuPtr
  // Backend committed,means that current commPtr block is done
  commPtr := commPtr + backendCommitNum
  // If block is accepted by IF, ifuPtr++
  // IB full should result in FU not accepting FTQ input
  when(io.ifuAccept & ~io.ifuFrontendRedirect) {
    ifuPtr := ifuPtr + 1.U
  }

  // bpu ptr
  when(io.bpuFtqPort.ftqP0.valid) {
    bpuPtr := bpuPtr + 1.U
  }.elsewhen((io.bpuFtqPort.mainBpuRedirectValid)) {
    // p1 redirect,maintain bpuPtr
    bpuPtr := bpuPtr
  }

  // if IF predecoder found a redirect
  when(io.instFetchFlush) {
    ifuPtr := io.instFetchFtqId + 1.U
    bpuPtr := io.instFetchFtqId + 1.U
  }
  // if backend redirect triggered,back to the next block of the redirect block
  // backend may continue to commit older block
  when(io.backendFlush) {
    ifuPtr := io.backendFlushFtqId + 1.U
    bpuPtr := io.backendFlushFtqId + 1.U
  }

  // next FTQ
  // Default no change
  ftqNextVec := ftqVec

  // clear out if committed
  Seq.range(0, issueNum).foreach { idx =>
    when(idx.U < backendCommitNum) {
      ftqNextVec(commPtr + idx.U) := 0.U
    }
  }

  // Accept BPU input
  // p0
  when(io.bpuFtqPort.ftqP0.valid) {
    ftqNextVec(bpuPtr) := io.bpuFtqPort.ftqP0
  }
  // p1
  when(io.bpuFtqPort.ftqP1.valid & ~mainBpuRedirectDelay) {
    // If last cycle accepted P0 input
    ftqNextVec(bpuPtr - 1.U) := io.bpuFtqPort.ftqP1
  }.elsewhen(io.bpuFtqPort.ftqP1.valid) {
    // else no override
    ftqNextVec(bpuPtr) := io.bpuFtqPort.ftqP1
  }

  // if predecoder redirect triggered,clear the committed and predicted entry
  when(io.instFetchFlush) {
    Seq.range(0, queueSize).foreach { idx =>
      when(idx.U(ptrWidth.W) - commPtr >= idx.U(ptrWidth.W) - io.instFetchFtqId) {
        ftqNextVec(idx) := 0.U
      }
    }
  }
  // if backend redirect triggered,clear the committed and predicted entry
  when(io.backendFlush) {
    Seq.range(0, queueSize).foreach { idx =>
      when(
        idx.U(ptrWidth.W) - commPtr >= idx.U(ptrWidth.W) - io.backendFlushFtqId && idx
          .U(ptrWidth.W) =/= io.backendFlushFtqId
      ) {
        ftqNextVec(idx) := 0.U
      }
    }
  }

  // Output
  // -> IFU
  io.ifuPort := ftqVec(ifuPtr)
  // Trigger a IFU flush when:
  // 1. last cycle send rreq to IFU
  // 2. main BPU redirect had modified the FTQ contents
  // 3. modified FTQ block is the rreq sent last cycle
  io.ifuFrontendRedirect := ifuFrontendRedirect
  // debug
  val debugLength = WireDefault(0.U(3.W))
  debugLength := io.ifuPort.length

  // -> Exe cuCommit query
  io.exeFtqPort.queryPcBundle.pc      := ftqVec(io.exeFtqPort.queryPcBundle.ftqId).startPc
  io.cuCommitFtqPort.queryPcBundle.pc := ftqVec(io.cuCommitFtqPort.queryPcBundle.ftqId).startPc

  // -> BPU
  io.bpuFtqPort.ftqFull := queueFull

  // training meta to BPU
  io.bpuFtqPort.ftqTrainMeta := FtqBpuMetaPort.default
  when(
    io.cuCommitFtqPort.blockBitmask(0) & io.cuCommitFtqPort.meta.isBranch
  ) {
    // Update when a branch is committed, defined as:
    // 1. Must be last in block, which means either a known branch or a mispredicted branch.
    // 2. Exception introduced block commit is not considered a branch update.
    val commitFtqId = WireDefault(io.cuCommitFtqPort.ftqId)
    io.bpuFtqPort.ftqTrainMeta.valid       := true.B
    io.bpuFtqPort.ftqTrainMeta.ftbHit      := ftqBpuMetaVec(commitFtqId).ftbHit
    io.bpuFtqPort.ftqTrainMeta.ftbHitIndex := ftqBpuMetaVec(commitFtqId).ftbHitIndex
    io.bpuFtqPort.ftqTrainMeta.ftbDirty    := ftqBranchMetaVec(commitFtqId).ftbDirty
    // Must use accuraate decoded info passed from backend
    io.bpuFtqPort.ftqTrainMeta.isBranch       := io.cuCommitFtqPort.meta.isBranch
    io.bpuFtqPort.ftqTrainMeta.branchType     := io.cuCommitFtqPort.meta.branchType
    io.bpuFtqPort.ftqTrainMeta.isTaken        := io.cuCommitFtqPort.meta.isTaken
    io.bpuFtqPort.ftqTrainMeta.predictedTaken := io.cuCommitFtqPort.meta.predictedTaken

    io.bpuFtqPort.ftqTrainMeta.startPc            := ftqVec(commitFtqId).startPc
    io.bpuFtqPort.ftqTrainMeta.isCrossCacheline   := ftqVec(commitFtqId).isCrossCacheline
    io.bpuFtqPort.ftqTrainMeta.bpuMeta            := ftqBpuMetaVec(commitFtqId).bpuMeta
    io.bpuFtqPort.ftqTrainMeta.jumpTargetAddress  := ftqBranchMetaVec(commitFtqId).jumpTargetAddress
    io.bpuFtqPort.ftqTrainMeta.fallThroughAddress := ftqBranchMetaVec(commitFtqId).fallThroughAddress
  }

  // Bpu meta ram
  // If last cycle accepted p1 input
  when(io.bpuFtqPort.ftqP1.valid & ~mainBpuRedirectDelay) {
    bpuMetaWriteValid             := true.B
    bpuMetaWritePtr               := bpuPtr - 1.U
    bpuMetaWriteEntry.ftbHit      := io.bpuFtqPort.ftqMeta.ftbHit
    bpuMetaWriteEntry.ftbHitIndex := io.bpuFtqPort.ftqMeta.ftbHitIndex
    bpuMetaWriteEntry.bpuMeta     := io.bpuFtqPort.ftqMeta.bpuMeta
  }.elsewhen(io.bpuFtqPort.ftqP1.valid) {
    bpuMetaWriteValid             := true.B
    bpuMetaWritePtr               := bpuPtr
    bpuMetaWriteEntry.ftbHit      := io.bpuFtqPort.ftqMeta.ftbHit
    bpuMetaWriteEntry.ftbHitIndex := io.bpuFtqPort.ftqMeta.ftbHitIndex
    bpuMetaWriteEntry.bpuMeta     := io.bpuFtqPort.ftqMeta.bpuMeta
  }.elsewhen(io.bpuFtqPort.ftqP0.valid) {
    // if not provided by BPU,clear meta
    bpuMetaWriteValid := true.B
    bpuMetaWritePtr   := bpuPtr
    bpuMetaWriteEntry := 0.U
  }.otherwise {
    bpuMetaWriteValid := false.B
    bpuMetaWritePtr   := 0.U
    bpuMetaWriteEntry := 0.U
  }

  // P1
  // maintain BPU meta info
  when(bpuMetaWriteValid) {
    ftqBpuMetaVec(bpuMetaWritePtr) := bpuMetaWriteEntry
  }
  // update pc from backend
  when(io.exeFtqPort.commitBundle.ftqMetaUpdateValid) {
    val ftqUpdateMetaId = WireDefault(io.exeFtqPort.commitBundle.ftqUpdateMetaId)
    ftqBranchMetaVec(
      ftqUpdateMetaId
    ).jumpTargetAddress := io.exeFtqPort.commitBundle.ftqMetaUpdateJumpTarget
    ftqBranchMetaVec(
      ftqUpdateMetaId
    ).fallThroughAddress                       := io.exeFtqPort.commitBundle.ftqMetaUpdateFallThrough
    ftqBranchMetaVec(ftqUpdateMetaId).ftbDirty := io.exeFtqPort.commitBundle.ftqMetaUpdateFtbDirty
  }

}
