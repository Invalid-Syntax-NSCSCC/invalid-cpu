package frontend

import spec._
import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import chisel3.experimental.Param
import frontend.bundles.{BackendCommitPort, FtqBlockPort, FtqBpuMetaPort}

class FetchTargetQueen(
  val queueSize: Int = Param.BPU.ftqSize,
  val issueNum:  Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    // <-> Frontend
    val backendFlush      = Input(Bool())
    val backendFlushFtqId = Input(UInt(log2Ceil(queueSize).W))
    val instFetchFlush    = Input(Bool())
    val instFetchFtqId    = Input(UInt(log2Ceil(queueSize).W))

    // <-> BPU
    val bpuP0           = Input(new FtqBlockPort)
    val bpuP1           = Input(new FtqBlockPort)
    val bpuMeta         = Input(new BpuFtqMetaPort)
    val mainBpuRedirect = Input(Bool())
    val bpuQueueFull    = Output(Bool())
    val bpuMetaO        = Output(new FtqBpuMetaPort)

    // <-> Backend
    val backendFtqMetaUpdateValid       = Input(Bool())
    val backendFtqMetaUpdateFtbDirty    = Input(Bool())
    val backendFtqMetaUpdateJumpTarget  = Input(UInt(spec.Width.Mem.addr))
    val backendFtqMetaUpdateFallThrough = Input(UInt(spec.Width.Mem.addr))
    val backendFtqUpdateMetaId          = Input(UInt(log2Ceil(queueSize).W))
    val backendCommitBitMask            = Input(UInt(issueNum.W))
    val backendCommitBlockBitmask       = Input(UInt(issueNum.W))
    val backendCommitFtqId              = Input(UInt(log2Ceil(issueNum).W))
    val backendCommitMeta               = Input(new BackendCommitPort)

    // <-> Ex query port
    val exQueryAddr = Input(UInt(queueSize.W))
    val exQueryPc   = Output(UInt(spec.Width.Mem.addr))

    // <-> IFU
    val ifuPort             = Output(new FtqBlockPort)
    val ifuFrontendRedirect = Output(Bool())
    val ifuFtqId            = Output(UInt(queueSize.W))
    val ifuAccept           = Input(Bool()) // Must return in the same cycle
  })
  // parameter
  val ptrWidth = log2Ceil(queueSize)
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
  val bpuPtrPlus1 = WireDefault(0.U(log2Ceil(queueSize).W))
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
  backendCommitNum := io.backendCommitBitMask.asBools.map(_.asUInt).reduce(_ +& _)

  // IF sent rreq
  ifuSendReq               := ftqVec(ifuPtr).valid & io.ifuAccept
  mainBpuRedirectModifyFtq := io.bpuP1.valid
  ifuFrontendRedirect      := (bpuPtr === (ifuPtr + 1.U)) & mainBpuRedirectModifyFtq & (ifuSendReq | ifuSendReqDelay)

  // queue full
  queueFull                := (bpuPtrPlus1 === commPtr)
  queueFullDelay           := RegNext(queueFull)
  ifuSendReqDelay          := RegNext(ifuSendReq)
  ifuFrontendRedirectDelay := RegNext(ifuFrontendRedirect)
  mainBpuRedirectDelay     := RegNext(io.mainBpuRedirect)

  ftqVec := RegNext(ftqNextVec)

//  //TODO debug signal
//  val debugQueuePcVec = Vec(queueSize, spec.Width.Mem._addr)
//  for (i <- 0 to queueSize) {
//    debugQueuePcVec(i) := ftqVec(i).startPc
//  }

  // ptr
  io.ifuFtqId := ifuPtr
  // Backend committed,means that current commPtr block is done
  commPtr := RegNext(commPtr + backendCommitNum)
  // If block is accepted by IF, ifuPtr++
  // IB full should result in FU not accepting FTQ input
  when(io.ifuAccept & ~io.ifuFrontendRedirect) {
    ifuPtr := RegNext(ifuPtr + 1.U)
  }

  // bpu ptr
  when(io.bpuP0.valid) {
    bpuPtr := RegNext(bpuPtr + 1)
  }.elsewhen((io.mainBpuRedirect)) {
    // p1 redirect,maintain bpuPtr
    bpuPtr := RegNext(bpuPtr)
  }

  // if IF predecoder found a redirect
  when(io.instFetchFlush) {
    ifuPtr := RegNext(io.instFetchFtqId + 1.U)
    bpuPtr := RegNext(io.instFetchFtqId + 1.U)
  }
  // if backend redirect triggered,back to the next block of the redirect block
  // backend may continue to commit older block
  when(io.backendFlush) {
    ifuPtr := RegNext(io.backendFlushFtqId + 1.U)
    bpuPtr := RegNext(io.backendFlushFtqId + 1.U)
  }

  // next FTQ
  ftqNextVec := ftqVec

  // clear out if committed
  for (i <- 0 to issueNum) {
    when(i.U < backendCommitNum) {
      ftqNextVec(commPtr + 1.U) := 0.U
    }
  }

  // Accept BPU input
  // p0
  when(io.bpuP0.valid) {
    ftqNextVec(bpuPtr) := io.bpuP0
  }
  // p1
  when(io.bpuP1.valid & ~mainBpuRedirectDelay) {
    // If last cycle accepted P0 input
    ftqNextVec(bpuPtr - 1.U) := io.bpuP1
  }.elsewhen(io.bpuP1.valid) {
    // else no override
    ftqNextVec(bpuPtr) := io.bpuP1
  }

  // if predecoder redirect triggered,clear the committed and predicted entry
  when(io.instFetchFlush) {
    for (i <- 0 to queueSize) {
      when(i.asUInt - commPtr >= i.asUInt - io.instFetchFtqId) {
        ftqNextVec(i) := 0.U
      }
    }
  }
  // if backend redirect triggered,clear the committed and predicted entry
  when(io.backendFlush) {
    for (i <- 0 to queueSize) {
      when(i.U - commPtr >= i.U - io.backendFlushFtqId && i.U =/= io.backendFlushFtqId) {
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
  io.bpuQueueFull := queueFull

  // training meta to BPU
  when(io.backendCommitBlockBitmask(0) & io.backendCommitMeta.isBranch) {
    // Update when a branch is committed, defined as:
    // 1. Must be last in block, which means either a known branch or a mispredicted branch.
    // 2. Exception introduced block commit is not considered a branch update.
    io.bpuMetaO.valid       := true.B
    io.bpuMetaO.ftbHit      := ftqBpuMetaVec(io.backendCommitFtqId).ftbHit
    io.bpuMetaO.ftbHitIndex := ftqBpuMetaVec(io.backendCommitFtqId).ftbHitIndex
    io.bpuMetaO.ftbDirty    := ftqBranchMetaVec(io.backendCommitFtqId).ftbDirty
    // Must use accuraate decoded info passed from backend
    io.bpuMetaO.isBranch       := io.backendCommitMeta.isBranch
    io.bpuMetaO.branchType     := io.backendCommitMeta.branchType
    io.bpuMetaO.isTaken        := io.backendCommitMeta.isTaken
    io.bpuMetaO.predictedTaken := io.backendCommitMeta.predictedTaken

    io.bpuMetaO.startPc            := ftqVec(io.backendCommitFtqId).startPc
    io.bpuMetaO.isCrossCacheline   := ftqVec(io.backendCommitFtqId).isCrossCacheline
    io.bpuMetaO.bpuMeta            := ftqBpuMetaVec(io.backendCommitFtqId).bpuMeta
    io.bpuMetaO.jumpTargetAddress  := ftqBranchMetaVec(io.backendCommitFtqId).jumpTargetAddress
    io.bpuMetaO.fallThroughAddress := ftqBranchMetaVec(io.backendCommitFtqId).fallThroughAddress
  }

  // Bpu meta ram
  // If last cycle accepted p1 input
  when(io.bpuP1.valid & ~mainBpuRedirectDelay) {
    bpuMetaWriteValid             := true.B
    bpuMetaWritePtr               := bpuPtr - 1.U
    bpuMetaWriteEntry.ftbHit      := io.bpuMeta.ftbHit
    bpuMetaWriteEntry.ftbHitIndex := io.bpuMeta.ftbHitIndex
    bpuMetaWriteEntry.bpuMeta     := io.bpuMeta.bpuMeta
  }.elsewhen(io.bpuP1.valid) {
    bpuMetaWriteValid             := true.B
    bpuMetaWritePtr               := bpuPtr
    bpuMetaWriteEntry.ftbHit      := io.bpuMeta.ftbHit
    bpuMetaWriteEntry.ftbHitIndex := io.bpuMeta.ftbHitIndex
    bpuMetaWriteEntry.bpuMeta     := io.bpuMeta.bpuMeta
  }.elsewhen(io.bpuP0.valid) {
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
    ftqBpuMetaVec(bpuMetaWritePtr) := RegNext(bpuMetaWriteEntry)
  }
  // update pc from backend
  when(io.backendFtqMetaUpdateValid) {
    ftqBranchMetaVec(io.backendFtqUpdateMetaId).jumpTargetAddress  := io.backendFtqMetaUpdateJumpTarget
    ftqBranchMetaVec(io.backendFtqUpdateMetaId).fallThroughAddress := io.backendFtqMetaUpdateFallThrough
    ftqBranchMetaVec(io.backendFtqUpdateMetaId).ftbDirty           := io.backendFtqMetaUpdateFtbDirty
  }

}
