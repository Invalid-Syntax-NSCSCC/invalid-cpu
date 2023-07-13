package frontend

import spec._
import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import chisel3.experimental.Param
import frontend.bundles.{BpuFtqPort, CuCommitFtqNdPort, ExeFtqPort, FtqBlockBundle, FtqBpuMetaPort, FtqIFNdPort}
import frontend.bundles.QueryPcBundle

// * io.ftqIFPort.valid 依赖 io.ftqIFPort.ready
class FetchTargetQueue(
  val queueSize: Int = Param.BPU.ftqSize,
  val commitNum: Int = Param.commitNum)
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
    val cuCommitFtqPort = Input(new CuCommitFtqNdPort)
    val cuQueryPcBundle = new QueryPcBundle
    // <-> Ex query port
    val exeFtqPort = new ExeFtqPort

    // <-> IFU
    val ftqIFPort = Decoupled(new FtqIFNdPort)
  })

  // Signals
  val queueFull                = WireDefault(false.B)
  val queueFullDelay           = RegInit(false.B)
  val ifSendValid              = WireInit(false.B)
  val ifSendValidDelay         = RegInit(false.B)
  val mainBpuRedirectDelay     = RegInit(false.B)
  val mainBpuRedirectModifyFtq = WireDefault(false.B)
  val ifRedirect               = WireDefault(false.B)
  val ifRedirectDelay          = RegInit(false.B)

  val bpuPtr      = RegInit(0.U(ptrWidth.W))
  val ifPtr       = RegInit(0.U(ptrWidth.W))
  val lastIfPtr   = RegNext(ifPtr, 0.U(ptrWidth.W))
  val commPtr     = RegInit(0.U(ptrWidth.W))
  val bpuPtrPlus1 = WireDefault(0.U(ptrWidth.W))
  bpuPtrPlus1 := bpuPtr + 1.U

  // Queue data structure
  // * enqueue from bpu
  val ftqVecReg  = RegInit(VecInit(Seq.fill(queueSize)(FtqBlockBundle.default)))
  val ftqNextVec = Wire(Vec(queueSize, new FtqBlockBundle))
  // FTQ meta
  val bpuMetaWriteValid = WireDefault(false.B)
  val bpuMetaWritePtr   = WireDefault(0.U(ptrWidth.W))
  val bpuMetaWriteEntry = WireDefault(BpuFtqMetaNdPort.default)
  val ftqBpuMetaRegs    = RegInit(VecInit(Seq.fill(queueSize)(BpuFtqMetaNdPort.default)))
  val ftqBranchMetaRegs = RegInit(VecInit(Seq.fill(queueSize)(FtqBranchMetaEntry.default)))

  val backendCommitNum = WireInit(0.U(log2Ceil(commitNum + 1).W))
  backendCommitNum := io.cuCommitFtqPort.bitMask.map(_.asUInt).reduce(_ +& _)

  // IF sent rreq
  // * valid & ready & no modify & no flush
  ifSendValid := io.ftqIFPort.bits.ftqBlockBundle.isValid &&
    io.ftqIFPort.ready &&
    !(ifPtr === bpuMetaWritePtr && bpuMetaWriteValid) && // to ifStage and modify at the same time
    !io.backendFlush
  mainBpuRedirectModifyFtq := io.bpuFtqPort.ftqP1.isValid

  // last block send dirty block;need to quit
  // * prev to ifStage and modify now
  ifRedirect := (bpuMetaWritePtr === lastIfPtr) && // lastIfPtr == ifPtr - 1
    (bpuMetaWritePtr + 1.U === ifPtr) &&
    mainBpuRedirectModifyFtq &&
    ifSendValidDelay

  // queue full
  queueFull            := bpuPtrPlus1 === commPtr
  queueFullDelay       := queueFull
  ifSendValidDelay     := ifSendValid
  ifRedirectDelay      := ifRedirect
  mainBpuRedirectDelay := io.bpuFtqPort.mainBpuRedirectValid

  ftqVecReg.zip(ftqNextVec).foreach {
    case (block, nextBlock) =>
      block := nextBlock
  }

  // ptr
  io.ftqIFPort.bits.ftqId := ifPtr

  // Backend committed,means that current commPtr block is done
  commPtr := commPtr + backendCommitNum
  // If block is accepted by IF, ifuPtr++
  // IB full should result in FU not accepting FTQ input
  when(ifSendValid & ~ifRedirect) {
    ifPtr := ifPtr + 1.U
  }

  // bpu ptr
  when(io.bpuFtqPort.mainBpuRedirectValid) {
    // p1 redirect,maintain bpuPtr
    bpuPtr := bpuPtr
  }.elsewhen(io.bpuFtqPort.ftqP0.isValid) {
    bpuPtr := bpuPtr + 1.U
  }

  // if IF predecoder found a redirect
  when(io.instFetchFlush) {
    ifPtr  := io.instFetchFtqId + 1.U
    bpuPtr := io.instFetchFtqId + 1.U
  }
  // if backend redirect triggered,back to the next block of the redirect block
  // backend may continue to commit older block (flush before exeStage inst;commit after exeStage inst)
  when(io.backendFlush) {
    ifPtr  := io.backendFlushFtqId + 1.U
    bpuPtr := io.backendFlushFtqId + 1.U
  }

  // next FTQ
  // Default no change
  ftqNextVec := ftqVecReg

  // clear out if committed
  // * no need
  Seq.range(0, commitNum).foreach { idx =>
    when(idx.U < backendCommitNum) {
      ftqNextVec(commPtr + idx.U) := FtqBlockBundle.default
    }
  }

  // Accept BPU input
  // p0
  when(io.bpuFtqPort.ftqP0.isValid) {
    ftqNextVec(bpuPtr) := io.bpuFtqPort.ftqP0
  }
  // p1
  when(io.bpuFtqPort.ftqP1.isValid && !mainBpuRedirectDelay) {
    // If last cycle accepted P0 input
    ftqNextVec(bpuPtr - 1.U) := io.bpuFtqPort.ftqP1
  }.elsewhen(io.bpuFtqPort.ftqP1.isValid) {
    // else no override
    ftqNextVec(bpuPtr) := io.bpuFtqPort.ftqP1
  }

  // if predecoder redirect triggered,clear the committed and predicted entry
  when(io.instFetchFlush) {
    Seq.range(0, queueSize).foreach { idx =>
      when(idx.U(ptrWidth.W) - commPtr >= idx.U(ptrWidth.W) - io.instFetchFtqId) {
        ftqNextVec(idx) := FtqBlockBundle.default
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
        ftqNextVec(idx) := FtqBlockBundle.default
      }
    }
  }

  // Output
  // -> IFU
  // default value
  io.ftqIFPort.valid               := ifSendValid
  io.ftqIFPort.bits.ftqBlockBundle := ftqVecReg(ifPtr)
  // design 1 : wtire through  ( has been abandoned)
  // feat: increase flush log;but easy to result in flush instfetch
//  when(((ifPtr === bpuMetaWritePtr)||(lastIfPtr === bpuMetaWritePtr)) && bpuMetaWriteValid) {
//    // write though
//    // case 1 bpuWrite and if read in the same time
//    // case 2 last if send block was dirty;need to resend
//    io.ftqIFPort.bits.ftqBlockBundle := ftqNextVec(bpuMetaWritePtr)
//  }
  // design 2::::::: only send correct ftq block

  // Trigger a IFU flush when:
  // 1. last cycle send rreq to IFU
  // 2. main BPU redirect had modified the FTQ contents
  // 3. modified FTQ block is the rreq sent last cycle
  io.ftqIFPort.bits.redirect := ifRedirect
  // debug
  val debugLength = WireDefault(0.U(3.W))
  debugLength := io.ftqIFPort.bits.ftqBlockBundle.length

  // -> Exe cuCommit query
  io.exeFtqPort.queryPcBundle.pc := ftqVecReg(io.exeFtqPort.queryPcBundle.ftqId).startPc
  io.cuQueryPcBundle.pc          := ftqVecReg(io.cuQueryPcBundle.ftqId).startPc

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
    io.bpuFtqPort.ftqTrainMeta.ftbHit      := ftqBpuMetaRegs(commitFtqId).ftbHit
    io.bpuFtqPort.ftqTrainMeta.ftbHitIndex := ftqBpuMetaRegs(commitFtqId).ftbHitIndex
    io.bpuFtqPort.ftqTrainMeta.ftbDirty    := ftqBranchMetaRegs(commitFtqId).ftbDirty
    // Must use accuraate decoded info passed from backend
    io.bpuFtqPort.ftqTrainMeta.isBranch       := io.cuCommitFtqPort.meta.isBranch
    io.bpuFtqPort.ftqTrainMeta.branchType     := io.cuCommitFtqPort.meta.branchType
    io.bpuFtqPort.ftqTrainMeta.isTaken        := io.cuCommitFtqPort.meta.isTaken
    io.bpuFtqPort.ftqTrainMeta.predictedTaken := io.cuCommitFtqPort.meta.predictedTaken

    io.bpuFtqPort.ftqTrainMeta.startPc            := ftqVecReg(commitFtqId).startPc
    io.bpuFtqPort.ftqTrainMeta.isCrossCacheline   := ftqVecReg(commitFtqId).isCrossCacheline
    io.bpuFtqPort.ftqTrainMeta.bpuMeta            := ftqBpuMetaRegs(commitFtqId).bpuMeta
    io.bpuFtqPort.ftqTrainMeta.jumpTargetAddress  := ftqBranchMetaRegs(commitFtqId).jumpTargetAddr
    io.bpuFtqPort.ftqTrainMeta.fallThroughAddress := ftqBranchMetaRegs(commitFtqId).fallThroughAddr
  }

  // Bpu meta ram
  // If last cycle accepted p1 input
  when(io.bpuFtqPort.ftqP1.isValid & ~mainBpuRedirectDelay) {
    bpuMetaWriteValid             := true.B
    bpuMetaWritePtr               := bpuPtr - 1.U
    bpuMetaWriteEntry.ftbHit      := io.bpuFtqPort.ftqMeta.ftbHit
    bpuMetaWriteEntry.ftbHitIndex := io.bpuFtqPort.ftqMeta.ftbHitIndex
    bpuMetaWriteEntry.bpuMeta     := io.bpuFtqPort.ftqMeta.bpuMeta
  }.elsewhen(io.bpuFtqPort.ftqP1.isValid) {
    bpuMetaWriteValid             := true.B
    bpuMetaWritePtr               := bpuPtr
    bpuMetaWriteEntry.ftbHit      := io.bpuFtqPort.ftqMeta.ftbHit
    bpuMetaWriteEntry.ftbHitIndex := io.bpuFtqPort.ftqMeta.ftbHitIndex
    bpuMetaWriteEntry.bpuMeta     := io.bpuFtqPort.ftqMeta.bpuMeta
  }.elsewhen(io.bpuFtqPort.ftqP0.isValid) {
    // if not provided by BPU,clear meta
    bpuMetaWriteValid := true.B
    bpuMetaWritePtr   := bpuPtr
    bpuMetaWriteEntry := BpuFtqMetaNdPort.default
  }.otherwise {
    bpuMetaWriteValid := false.B
    bpuMetaWritePtr   := 0.U
    bpuMetaWriteEntry := BpuFtqMetaNdPort.default
  }

  // P1
  // maintain BPU meta info
  when(bpuMetaWriteValid) {
    ftqBpuMetaRegs(bpuMetaWritePtr) := bpuMetaWriteEntry
  }
  // update pc from backend
  when(io.exeFtqPort.commitBundle.ftqMetaUpdateValid) {
    val ftqUpdateMetaId = WireDefault(io.exeFtqPort.commitBundle.ftqUpdateMetaId)
    ftqBranchMetaRegs(
      ftqUpdateMetaId
    ).jumpTargetAddr := io.exeFtqPort.commitBundle.ftqMetaUpdateJumpTarget
    ftqBranchMetaRegs(
      ftqUpdateMetaId
    ).fallThroughAddr                           := io.exeFtqPort.commitBundle.ftqMetaUpdateFallThrough
    ftqBranchMetaRegs(ftqUpdateMetaId).ftbDirty := io.exeFtqPort.commitBundle.ftqMetaUpdateFtbDirty
  }

}
