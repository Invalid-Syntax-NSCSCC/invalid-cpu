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
    val backendFlush          = Input(Bool())
    val backendFlushFtqId     = Input(UInt(ptrWidth.W))
    val preDecoderFlush       = Input(Bool())
    val preDecoderFtqId       = Input(UInt(ptrWidth.W))
    val preDecoderBranchTaken = Input(Bool())

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
  val isFlush                  = WireDefault(false.B)
  val isFlushDelay             = RegNext(isFlush, false.B)
  isFlush := io.backendFlush || io.preDecoderFlush
  val backendFlushIdReg    = RegInit(0.U(ptrWidth.W))
  val preDecoderFlushIdReg = RegInit(0.U(ptrWidth.W))
  backendFlushIdReg    := io.backendFlushFtqId
  preDecoderFlushIdReg := io.preDecoderFtqId
  // flush Enable delay 1 circle than id (to decrease mux netDelay)

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

  val ftqIfBits = WireDefault(FtqIFNdPort.default)

  // IF sent rreq
  // * valid & ready & no modify & no flush
  ifSendValid := ftqIfBits.ftqBlockBundle.isValid && !isFlush && !isFlushDelay
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
  ftqIfBits.ftqId := ifPtr

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
  when(io.preDecoderFlush) {
    nextIfPtr := preDecoderFlushIdReg + 1.U
    bpuPtr    := preDecoderFlushIdReg + 1.U
  }
  // if backend redirect triggered,back to the next block of the redirect block
  // backend may continue to commit older block (flush before exeStage inst;commit after exeStage inst)
  when(io.backendFlush) {
    nextIfPtr := backendFlushIdReg + 1.U
    bpuPtr    := backendFlushIdReg + 1.U
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
  io.ftqIFPort.valid       := ifSendValid
  ftqIfBits.ftqBlockBundle := RegNext(ftqNextVec(nextIfPtr))
  io.ftqIFPort.bits        := ftqIfBits // use RegNext and nextPtr to decrease net delay
  // design 1 : wtire through  ( has been abandoned)
  // feat: increase flush log;but easy to result in flush instfetch
//  when(((ifPtr === bpuMetaWritePtr)||(lastIfPtr === bpuMetaWritePtr)) && bpuMetaWriteValid) {
//    // write though
//    // case 1 bpuWrite and if read in the same time
//    // case 2 last if send block was dirty;need to resend
//    io.ftqIFPort.bits.ftqBlockBundle := ftqNextVec(bpuMetaWritePtr)
//  }
  // design 2::::::: only send correct ftq block; (too high logic dealy)

  // Trigger a IFU flush when:
  // 1. last cycle send rreq to IFU
  // 2. main BPU redirect had modified the FTQ contents
  // 3. modified FTQ block is the rreq sent last cycle
  ftqIfBits.redirect := ifRedirect

  // Save out; when ftq out valid and ready,but in the next clock not ready,save the resultOut
  // when isNoPrivilege;which means no tlb and no addrStages
  val saveOutBits  = dontTouch(RegInit(FtqIFNdPort.default))
  val saveOutValid = RegInit(false.B)
  when(io.ftqIFPort.ready && io.ftqIFPort.valid) {
    saveOutBits  := ftqIfBits
    saveOutValid := ifSendValid
  }
  when(isFlush) {
    saveOutValid := false.B
  }

  if (Param.isNoPrivilege) {
    when(!io.ftqIFPort.ready) {
      io.ftqIFPort.bits  := saveOutBits
      io.ftqIFPort.valid := saveOutValid
    }
  }

  // -> Exe cuCommit query
  io.exeFtqPort.queryPcBundle.pc := ftqVecReg(io.exeFtqPort.queryPcBundle.ftqId).startPc

  // -> BPU
  io.bpuFtqPort.ftqFull := queueFull

  // training meta to BPU
  io.bpuFtqPort.ftqBpuTrainMeta                                               := FtqBpuMetaPort.default
  io.bpuFtqPort.ftqBpuTrainMeta.ghrUpdateSignalBundle.exeFixBundle            := io.exeFtqPort.feedBack.fixGhrBundle
  io.bpuFtqPort.ftqBpuTrainMeta.ghrUpdateSignalBundle.isPredecoderFixGhr      := io.preDecoderFlush
  io.bpuFtqPort.ftqBpuTrainMeta.ghrUpdateSignalBundle.isPredecoderBranchTaken := io.preDecoderBranchTaken
  io.bpuFtqPort.ftqBpuTrainMeta.tageGhrInfo :=
    Mux(
      io.backendFlush,
      ftqBpuMetaRegs(backendFlushIdReg).tageQueryMeta.tageGhrInfo,
      ftqBpuMetaRegs(preDecoderFlushIdReg).tageQueryMeta.tageGhrInfo
    )
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

  io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.startPc := ftqVecReg(commitFtqId).startPc
  io.bpuFtqPort.ftqBpuTrainMeta.isCrossCacheline         := ftqVecReg(commitFtqId).isCrossCacheline
  io.bpuFtqPort.ftqBpuTrainMeta.tageOriginMeta           := ftqBpuMetaRegs(commitFtqId).tageQueryMeta
  io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.jumpPartialTargetAddr := ftqBranchMetaRegs(commitFtqId).jumpTargetAddr(
    spec.Width.Mem._addr - 1,
    2
  )
  io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.fetchLastIdx := ftqBranchMetaRegs(commitFtqId).fetchLastIdx

  // commit to ras
//  io.ftqRasPort.valid         := RegNext(io.commitFtqTrainPort.isTrainValid, false.B)
//  io.ftqRasPort.bits.isPush   := RegNext(io.commitFtqTrainPort.branchTakenMeta.branchType === BranchType.call, false.B)
//  io.ftqRasPort.bits.isPop    := RegNext(io.commitFtqTrainPort.branchTakenMeta.branchType === BranchType.ret, false.B)
//  io.ftqRasPort.bits.callAddr := RegNext(ftqBranchMetaRegs(commitFtqId).fallThroughAddr, 0.U)
//  io.ftqRasPort.bits.predictError := RegNext(
//    ftqBranchMetaRegs(
//      commitFtqId
//    ).ftbDirty || (io.commitFtqTrainPort.branchTakenMeta.predictedTaken ^ io.commitFtqTrainPort.branchTakenMeta.isTaken),
//    false.B
//  )
  io.ftqRasPort.valid       := io.commitFtqTrainPort.isTrainValid
  io.ftqRasPort.bits.isPush := io.commitFtqTrainPort.branchTakenMeta.branchType === BranchType.call
  io.ftqRasPort.bits.isPop  := io.commitFtqTrainPort.branchTakenMeta.branchType === BranchType.ret
  io.ftqRasPort.bits.callAddr := ftqVecReg(commitFtqId).startPc + ((ftqBranchMetaRegs(
    commitFtqId
  ).fetchLastIdx +& 1.U) << 2)
  io.ftqRasPort.bits.predictError :=
    ftqBranchMetaRegs(
      commitFtqId
    ).ftbDirty || (io.commitFtqTrainPort.branchTakenMeta.predictedTaken ^ io.commitFtqTrainPort.branchTakenMeta.isTaken)

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
  bpuMetaWriteEntry.tageQueryMeta.tageGhrInfo := io.bpuFtqPort.bpuQueryMeta.tageQueryMeta.tageGhrInfo
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
  when(io.exeFtqPort.feedBack.commitBundle.ftqMetaUpdateValid) {
    val ftqUpdateMetaId = WireDefault(io.exeFtqPort.feedBack.commitBundle.ftqUpdateMetaId)
    ftqBranchMetaRegs(
      ftqUpdateMetaId
    ).jumpTargetAddr := io.exeFtqPort.feedBack.commitBundle.ftqMetaUpdateJumpTarget
    ftqBranchMetaRegs(
      ftqUpdateMetaId
    ).fetchLastIdx                                  := io.exeFtqPort.feedBack.commitBundle.ftqMetaUpdateFallThrough
    ftqBranchMetaRegs(ftqUpdateMetaId).ftbDirty     := io.exeFtqPort.feedBack.commitBundle.ftqMetaUpdateFtbDirty
    ftqBranchMetaRegs(ftqUpdateMetaId).fetchLastIdx := io.exeFtqPort.feedBack.commitBundle.fetchLastIdx
  }

  if (Param.isNoPrivilege) {
    // when without tlb, all info commit at exe Stage
    io.bpuFtqPort.ftqBpuTrainMeta.ftbDirty := io.exeFtqPort.feedBack.commitBundle.ftqMetaUpdateFtbDirty
    io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.jumpPartialTargetAddr := io.exeFtqPort.feedBack.commitBundle
      .ftqMetaUpdateJumpTarget(
        spec.Width.Mem._addr - 1,
        2
      )
    io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.fetchLastIdx := io.exeFtqPort.feedBack.commitBundle.fetchLastIdx

    io.ftqRasPort.bits.callAddr := ftqVecReg(
      commitFtqId
    ).startPc + ((io.exeFtqPort.feedBack.commitBundle.fetchLastIdx +& 1.U) << 2)
    io.ftqRasPort.bits.predictError :=
      io.exeFtqPort.feedBack.commitBundle.ftqMetaUpdateFtbDirty ||
        (io.commitFtqTrainPort.branchTakenMeta.predictedTaken ^ io.commitFtqTrainPort.branchTakenMeta.isTaken)
  }

}
