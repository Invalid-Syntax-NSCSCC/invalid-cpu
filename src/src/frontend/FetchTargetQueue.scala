package frontend

import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import frontend.bundles._
import frontend.fetch.ftqPreDecodeFixRasNdPort
import spec.Param.BPU.BranchType
import spec._

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
    val commitFtqTrainPort = Input(new CommitFtqTrainNdPort)
    val commitBitMask      = Input(Vec(Param.commitNum, Bool()))

    // <-> Ex query port
    val exeFtqPort = new ExeFtqPort

    // <-> IFU
    val ftqIFPort = Decoupled(new FtqIFNdPort)

    // <-> RAS (in predecode stage)
    val ftqRasPort = Valid(new ftqPreDecodeFixRasNdPort)
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

  val bpuPtr    = RegInit(0.U(ptrWidth.W))
  val ifPtr     = RegInit(0.U(ptrWidth.W))
  val nextIfPtr = WireDefault(ifPtr)
  ifPtr := nextIfPtr
  val commPtr     = RegInit(0.U(ptrWidth.W))
  val bpuPtrPlus1 = WireDefault(0.U(ptrWidth.W))
  bpuPtrPlus1 := bpuPtr + 1.U

  // Queue data structure
  //  enqueue from bpu
  val ftqVecReg  = RegInit(VecInit(Seq.fill(queueSize)(FtqBlockBundle.default)))
  val ftqNextVec = Wire(Vec(queueSize, new FtqBlockBundle))
  // FTQ meta
  val bpuMetaWriteValid = WireDefault(false.B)
  val bpuMetaWritePtr   = WireDefault(0.U(ptrWidth.W))
  val bpuMetaWriteEntry = WireDefault(BpuFtqMetaNdPort.default)
  val ftqBpuMetaRegs    = RegInit(VecInit(Seq.fill(queueSize)(BpuFtqMetaNdPort.default)))
  val ftqBranchMetaRegs = RegInit(VecInit(Seq.fill(queueSize)(FtqBranchMetaEntry.default)))

  val backendCommitNum = WireInit(0.U(log2Ceil(commitNum + 1).W))
  backendCommitNum := io.commitBitMask.map(_.asUInt).reduce(_ +& _)

  // IF sent rreq
  // * valid & ready & no modify & no flush
  ifSendValid := io.ftqIFPort.bits.ftqBlockBundle.isValid
//  && !(ifPtr === bpuMetaWritePtr && bpuMetaWriteValid) // to ifStage and modify at the same time
//    !io.backendFlush   ( instAddr would not ready when flush, so valid needn't insure no flush in order to simply logic
  mainBpuRedirectModifyFtq := io.bpuFtqPort.ftqP1.isValid

  // last block send dirty block;need to quit
  // * prev to ifStage and modify now
  ifRedirect := bpuMetaWriteValid && (bpuMetaWritePtr === ifPtr) // && ifSendValid // (bpuMetaWritePtr === lastIfPtr) && // lastIfPtr == ifPtr - 1
  // mainBpuRedirectModifyFtq &&

  // queue full
  queueFull            := bpuPtrPlus1 === commPtr
  queueFullDelay       := queueFull
  ifSendValidDelay     := ifSendValid
  ifRedirectDelay      := ifRedirect
  mainBpuRedirectDelay := io.bpuFtqPort.bpuRedirectValid

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
  when(ifSendValid && io.ftqIFPort.ready && !ifRedirect) {
    nextIfPtr := ifPtr + 1.U
  }

  // bpu ptr
  when(io.bpuFtqPort.bpuRedirectValid) {
    // p1 redirect,maintain bpuPtr
    bpuPtr := bpuPtr
  }.elsewhen(io.bpuFtqPort.ftqP0.isValid) {
    bpuPtr := bpuPtr + 1.U
  }

  // if IF predecoder found a redirect
  when(io.instFetchFlush) {
    nextIfPtr := io.instFetchFtqId + 1.U
    bpuPtr    := io.instFetchFtqId + 1.U
  }
  // if backend redirect triggered,back to the next block of the redirect block
  // backend may continue to commit older block (flush before exeStage inst;commit after exeStage inst)
  when(io.backendFlush) {
    nextIfPtr := io.backendFlushFtqId + 1.U
    bpuPtr    := io.backendFlushFtqId + 1.U
  }

  // next FTQ
  // Default no change
  ftqNextVec := ftqVecReg

  // clear out if committed
  // * no need
  Seq.range(0, commitNum).foreach { idx =>
    when(idx.U < backendCommitNum) {
      ftqNextVec(commPtr + idx.U).isValid := false.B // := FtqBlockBundle.default
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

  // Output
  // -> IFU
  // default value
  io.ftqIFPort.valid := ifSendValid
  io.ftqIFPort.bits.ftqBlockBundle := RegNext(
    ftqNextVec(nextIfPtr),
    FtqBlockBundle.default
  ) // use RegNext and nextPtr to decrease net delay
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

  // -> BPU
  io.bpuFtqPort.ftqFull := queueFull

  // training meta to BPU
  io.bpuFtqPort.ftqBpuTrainMeta := FtqBpuMetaPort.default
//  when(
//    io.cuCommitFtqPort.blockBitmask(0) & io.cuCommitFtqPort.meta.isBranch
//  ) {
  // Update when a branch is committed, defined as:
  // 1. Must be last in block, which means either a known branch or a mispredicted branch.
  // 2. Exception introduced block commit is not considered a branch update.
  val commitFtqId = WireDefault(io.commitFtqTrainPort.ftqId)
  io.bpuFtqPort.ftqBpuTrainMeta.valid       := io.commitFtqTrainPort.isTrainValid
  io.bpuFtqPort.ftqBpuTrainMeta.ftbHit      := ftqBpuMetaRegs(commitFtqId).ftbHit
  io.bpuFtqPort.ftqBpuTrainMeta.ftbHitIndex := ftqBpuMetaRegs(commitFtqId).ftbHitIndex
  io.bpuFtqPort.ftqBpuTrainMeta.ftbDirty    := ftqBranchMetaRegs(commitFtqId).ftbDirty // jumpTargetAddr error
  // Must use accuraate decoded info passed from backend
  io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta := io.commitFtqTrainPort.branchTakenMeta

  io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.startPc         := ftqVecReg(commitFtqId).startPc
  io.bpuFtqPort.ftqBpuTrainMeta.isCrossCacheline                 := ftqVecReg(commitFtqId).isCrossCacheline
  io.bpuFtqPort.ftqBpuTrainMeta.tageOriginMeta                         := ftqBpuMetaRegs(commitFtqId).tageQueryMeta
  io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.jumpTargetAddr  := ftqBranchMetaRegs(commitFtqId).jumpTargetAddr
  io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.fallThroughAddr := ftqBranchMetaRegs(commitFtqId).fallThroughAddr

  // commit to ras
  io.ftqRasPort.valid         := io.commitFtqTrainPort.isTrainValid
  io.ftqRasPort.bits.isPush   := io.commitFtqTrainPort.branchTakenMeta.branchType === BranchType.call
  io.ftqRasPort.bits.isPop    := io.commitFtqTrainPort.branchTakenMeta.branchType === BranchType.ret
  io.ftqRasPort.bits.callAddr := ftqBranchMetaRegs(commitFtqId).fallThroughAddr
  io.ftqRasPort.bits.predictError := ftqBranchMetaRegs(
    commitFtqId
  ).ftbDirty || (io.commitFtqTrainPort.branchTakenMeta.predictedTaken ^ io.commitFtqTrainPort.branchTakenMeta.isTaken)
//  }

  // Bpu meta ram
  // If last cycle accepted p1 input
  bpuMetaWriteValid := io.bpuFtqPort.ftqP0.isValid || io.bpuFtqPort.ftqP1.isValid
  bpuMetaWritePtr := Mux(
    io.bpuFtqPort.ftqP1.isValid && !mainBpuRedirectDelay,
    bpuPtr - 1.U,
    bpuPtr
  )
  bpuMetaWriteEntry := Mux(
    io.bpuFtqPort.ftqP1.isValid,
    io.bpuFtqPort.bpuQueryMeta,
    BpuFtqMetaNdPort.default
  )
//  when(io.bpuFtqPort.ftqP1.isValid & ~mainBpuRedirectDelay) {
//    bpuMetaWriteValid             := true.B
//    bpuMetaWritePtr               := bpuPtr - 1.U
//    bpuMetaWriteEntry.ftbHit      := io.bpuFtqPort.ftqMeta.ftbHit
//    bpuMetaWriteEntry.ftbHitIndex := io.bpuFtqPort.ftqMeta.ftbHitIndex
//    bpuMetaWriteEntry.bpuMeta     := io.bpuFtqPort.ftqMeta.bpuMeta
//  }.elsewhen(io.bpuFtqPort.ftqP1.isValid) {
//    bpuMetaWriteValid             := true.B
//    bpuMetaWritePtr               := bpuPtr
//    bpuMetaWriteEntry.ftbHit      := io.bpuFtqPort.ftqMeta.ftbHit
//    bpuMetaWriteEntry.ftbHitIndex := io.bpuFtqPort.ftqMeta.ftbHitIndex
//    bpuMetaWriteEntry.bpuMeta     := io.bpuFtqPort.ftqMeta.bpuMeta
//  }.elsewhen(io.bpuFtqPort.ftqP0.isValid) {
//    // if not provided by BPU,clear meta
//    bpuMetaWriteValid := true.B
//    bpuMetaWritePtr   := bpuPtr
//    bpuMetaWriteEntry := BpuFtqMetaNdPort.default
//  }.otherwise {
//    bpuMetaWriteValid := false.B
//    bpuMetaWritePtr   := 0.U
//    bpuMetaWriteEntry := BpuFtqMetaNdPort.default
//  }

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
