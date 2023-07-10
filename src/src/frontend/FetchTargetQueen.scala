package frontend

import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import frontend.bundles.{BackendCommitPort, BpuFtqPort, FtqBlockPort, FtqBpuMetaPort}
import spec._

class FetchTargetQueen(
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
    val backendCommitPort = Input(new BackendCommitPort())

    // <-> Ex query port
    val exQueryAddr = Input(UInt(ptrWidth.W))
    val exQueryPc   = Output(UInt(spec.Width.Mem.addr))

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
  backendCommitNum := io.backendCommitPort.commitBitMask.asBools.map(_.asUInt).reduce(_ +& _)

  // IF sent rreq
  ifuSendReq               := ftqVec(ifuPtr).valid & io.ifuAccept
  mainBpuRedirectModifyFtq := io.bpuFtqPort.ftqP1.valid
  ifuFrontendRedirect      := (bpuPtr === (ifuPtr + 1.U)) & mainBpuRedirectModifyFtq & (ifuSendReq | ifuSendReqDelay)

  // queue full
  queueFull                := (bpuPtrPlus1 === commPtr)
  queueFullDelay           := RegNext(queueFull)
  ifuSendReqDelay          := RegNext(ifuSendReq)
  ifuFrontendRedirectDelay := RegNext(ifuFrontendRedirect)
  mainBpuRedirectDelay     := RegNext(io.bpuFtqPort.mainBpuRedirectValid)

  ftqVec := RegNext(ftqNextVec)

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
  for (i <- 0 to issueNum) {
    when(i.U < backendCommitNum) {
      ftqNextVec(commPtr + i.U) := 0.U
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
    for (i <- 0 to queueSize) {
      when(i.U(ptrWidth.W) - commPtr >= i.U(ptrWidth.W) - io.instFetchFtqId) {
        ftqNextVec(i) := 0.U
      }
    }
  }
  // if backend redirect triggered,clear the committed and predicted entry
  when(io.backendFlush) {
    for (i <- 0 to queueSize) {
      when(
        i.U(ptrWidth.W) - commPtr >= i.U(ptrWidth.W) - io.backendFlushFtqId && i.U(ptrWidth.W) =/= io.backendFlushFtqId
      ) {
        ftqNextVec(i) := 0.U
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

  // -> Ex
  io.exQueryPc := ftqVec(io.exQueryAddr).startPc

  // -> BPU
  io.bpuFtqPort.ftqFull := queueFull

  // training meta to BPU
  io.bpuFtqPort.ftqTrainMeta := FtqBpuMetaPort.default
  when(io.backendCommitPort.commitBlockBitmask(0) & io.backendCommitPort.commitMeta.isBranch) {
    // Update when a branch is committed, defined as:
    // 1. Must be last in block, which means either a known branch or a mispredicted branch.
    // 2. Exception introduced block commit is not considered a branch update.
    io.bpuFtqPort.ftqTrainMeta.valid       := true.B
    io.bpuFtqPort.ftqTrainMeta.ftbHit      := ftqBpuMetaVec(io.backendCommitPort.commitFtqId).ftbHit
    io.bpuFtqPort.ftqTrainMeta.ftbHitIndex := ftqBpuMetaVec(io.backendCommitPort.commitFtqId).ftbHitIndex
    io.bpuFtqPort.ftqTrainMeta.ftbDirty    := ftqBranchMetaVec(io.backendCommitPort.commitFtqId).ftbDirty
    // Must use accuraate decoded info passed from backend
    io.bpuFtqPort.ftqTrainMeta.isBranch       := io.backendCommitPort.commitMeta.isBranch
    io.bpuFtqPort.ftqTrainMeta.branchType     := io.backendCommitPort.commitMeta.branchType
    io.bpuFtqPort.ftqTrainMeta.isTaken        := io.backendCommitPort.commitMeta.isTaken
    io.bpuFtqPort.ftqTrainMeta.predictedTaken := io.backendCommitPort.commitMeta.predictedTaken

    io.bpuFtqPort.ftqTrainMeta.startPc           := ftqVec(io.backendCommitPort.commitFtqId).startPc
    io.bpuFtqPort.ftqTrainMeta.isCrossCacheline  := ftqVec(io.backendCommitPort.commitFtqId).isCrossCacheline
    io.bpuFtqPort.ftqTrainMeta.bpuMeta           := ftqBpuMetaVec(io.backendCommitPort.commitFtqId).bpuMeta
    io.bpuFtqPort.ftqTrainMeta.jumpTargetAddress := ftqBranchMetaVec(io.backendCommitPort.commitFtqId).jumpTargetAddress
    io.bpuFtqPort.ftqTrainMeta.fallThroughAddress := ftqBranchMetaVec(
      io.backendCommitPort.commitFtqId
    ).fallThroughAddress
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
  when(io.backendCommitPort.ftqMetaUpdateValid) {
    ftqBranchMetaVec(
      io.backendCommitPort.ftqUpdateMetaId
    ).jumpTargetAddress := io.backendCommitPort.ftqMetaUpdateJumpTarget
    ftqBranchMetaVec(
      io.backendCommitPort.ftqUpdateMetaId
    ).fallThroughAddress                                            := io.backendCommitPort.ftqMetaUpdateFallThrough
    ftqBranchMetaVec(io.backendCommitPort.ftqUpdateMetaId).ftbDirty := io.backendCommitPort.ftqMetaUpdateFtbDirty
  }

}
