package frontend

import spec._
import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import chisel3.experimental.Param
import frontend.bundles.{BpuFtqPort, CuCommitFtqNdPort, ExeFtqPort, FtqBlockBundle, FtqBpuMetaPort, FtqIFNdPort}
import frontend.bundles.QueryPcBundle
import pipeline.queue.MultiInstQueue
import pipeline.common.MultiQueue

// * io.ftqIFPort.valid 依赖 io.ftqIFPort.ready
class NewFetchTargetQueue(
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

  // Queue data structure
  // * enqueue from bpu
  // * dequeue from commit
  val ftq = Module(
    new MultiQueue(
      queueSize,
      1,
      commitNum,
      new FtqBlockBundle,
      FtqBlockBundle.default,
      writeFirst       = true,
      supportSetEnqPtr = true
    )
  )
  ftq.io.enqueuePorts.foreach { enq =>
    enq.valid := false.B
    enq.bits  := DontCare
  }
  ftq.io.isFlush := false.B
  ftq.io.setPorts.zip(ftq.io.elems).foreach {
    case (dst, src) =>
      dst.valid := false.B
      dst.bits  := src
  }
  ftq.io.enqPtrSetPort.get.valid := false.B
  ftq.io.enqPtrSetPort.get.bits  := DontCare

  // val backendCommitNum = WireInit(0.U(log2Ceil(commitNum).W))
  // backendCommitNum := io.cuCommitFtqPort.bitMask.map(_.asUInt).reduce(_ +& _)
  io.cuCommitFtqPort.bitMask.zip(ftq.io.dequeuePorts).foreach {
    case (cmtValid, deq) =>
      deq.ready := cmtValid
  }

  val queueFull = WireDefault(ftq.io.enqueuePorts.head.ready)

  val bpuPtr    = ftq.io.enq_ptr
  val ifPtr     = RegInit(0.U(ptrWidth.W))
  val lastIfPtr = RegNext(ifPtr, 0.U(ptrWidth.W))

  val bpuMetaWritePort = WireDefault(0.U.asTypeOf(new Bundle {
    val valid = Bool()
    val ptr   = UInt(ptrWidth.W)
    val entry = new BpuFtqMetaPort
  }))

  val ftqBpuMetaRegs    = RegInit(VecInit(Seq.fill(queueSize)(BpuFtqMetaPort.default)))
  val ftqBranchMetaRegs = RegInit(VecInit(Seq.fill(queueSize)(FtqBranchMetaEntry.default)))

  // IF sent rreq
  // * valid & ready & no modify & no flush
  val isSendValid = io.ftqIFPort.bits.ftqBlockBundle.isValid &&
    io.ftqIFPort.ready &&
    !(ifPtr === bpuMetaWritePort.ptr && bpuMetaWritePort.valid) && // to ifStage and modify at the same time
    !io.backendFlush

  val isSendValidDelay = RegNext(isSendValid, false.B)

  val mainBpuRedirectModifyFtq = WireDefault(io.bpuFtqPort.ftqP1.isValid)

  // last block send dirty block;need to quit
  // * prev to ifStage and modify now
  val isRedirect = (bpuMetaWritePort.ptr === lastIfPtr) && // lastIfPtr == ifPtr - 1
    (bpuMetaWritePort.ptr + 1.U === ifPtr) &&
    mainBpuRedirectModifyFtq &&
    isSendValidDelay

  val mainBpuRedirectDelay = RegNext(io.bpuFtqPort.mainBpuRedirectValid, false.B)

  io.ftqIFPort.bits.ftqId := ifPtr

  when(isSendValid && !isRedirect) {
    ifPtr := ifPtr + 1.U
  }

  // bpu ptr
  ftq.io.enqueuePorts.head.valid :=
    io.bpuFtqPort.ftqP0.isValid &&
      !io.bpuFtqPort.mainBpuRedirectValid

  // TODO: 搞清楚哪些是入队哪些是修改
  ftq.io.setPorts(bpuPtr).valid :=
    io.bpuFtqPort.ftqP0.isValid || (
      io.bpuFtqPort.ftqP1.isValid && mainBpuRedirectDelay
    )
  ftq.io.setPorts(bpuPtr).bits := Mux(
    io.bpuFtqPort.ftqP0.isValid,
    io.bpuFtqPort.ftqP0,
    io.bpuFtqPort.ftqP1
  )

  ftq.io.setPorts(bpuPtr - 1.U).valid :=
    io.bpuFtqPort.ftqP1.isValid &&
      !mainBpuRedirectDelay
  ftq.io.setPorts(bpuPtr - 1.U).bits := io.bpuFtqPort.ftqP1

  // if IF predecoder found a redirect
  when(io.instFetchFlush) {
    ifPtr                          := io.instFetchFtqId + 1.U
    ftq.io.enqPtrSetPort.get.valid := true.B
    ftq.io.enqPtrSetPort.get.bits  := io.instFetchFtqId + 1.U
  }

  when(io.backendFlush) {
    ifPtr                          := io.backendFlushFtqId + 1.U
    ftq.io.enqPtrSetPort.get.valid := true.B
    ftq.io.enqPtrSetPort.get.bits  := io.backendFlushFtqId + 1.U
  }

  // Output
  // -> IFU
  // default value
  io.ftqIFPort.valid               := isSendValid
  io.ftqIFPort.bits.ftqBlockBundle := ftq.io.elems(ifPtr)
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
  io.ftqIFPort.bits.redirect := isRedirect

  // -> Exe cuCommit query
  io.exeFtqPort.queryPcBundle.pc := ftq.io.elems(io.exeFtqPort.queryPcBundle.ftqId).startPc
  io.cuQueryPcBundle.pc          := ftq.io.elems(io.cuQueryPcBundle.ftqId).startPc

  // -> BPU
  io.bpuFtqPort.ftqFull := queueFull

  // training meta to BPU

  // Update when a branch is committed, defined as:
  // 1. Must be last in block, which means either a known branch or a mispredicted branch.
  // 2. Exception introduced block commit is not considered a branch update.
  val commitFtqId = WireDefault(io.cuCommitFtqPort.ftqId)
  io.bpuFtqPort.ftqTrainMeta.valid       := io.cuCommitFtqPort.blockBitmask(0) && io.cuCommitFtqPort.meta.isBranch
  io.bpuFtqPort.ftqTrainMeta.ftbHit      := ftqBpuMetaRegs(commitFtqId).ftbHit
  io.bpuFtqPort.ftqTrainMeta.ftbHitIndex := ftqBpuMetaRegs(commitFtqId).ftbHitIndex
  io.bpuFtqPort.ftqTrainMeta.ftbDirty    := ftqBranchMetaRegs(commitFtqId).ftbDirty
  // Must use accuraate decoded info passed from backend
  io.bpuFtqPort.ftqTrainMeta.isBranch       := io.cuCommitFtqPort.meta.isBranch
  io.bpuFtqPort.ftqTrainMeta.branchType     := io.cuCommitFtqPort.meta.branchType
  io.bpuFtqPort.ftqTrainMeta.isTaken        := io.cuCommitFtqPort.meta.isTaken
  io.bpuFtqPort.ftqTrainMeta.predictedTaken := io.cuCommitFtqPort.meta.predictedTaken

  io.bpuFtqPort.ftqTrainMeta.startPc            := ftq.io.elems(commitFtqId).startPc
  io.bpuFtqPort.ftqTrainMeta.isCrossCacheline   := ftq.io.elems(commitFtqId).isCrossCacheline
  io.bpuFtqPort.ftqTrainMeta.bpuMeta            := ftqBpuMetaRegs(commitFtqId).bpuMeta
  io.bpuFtqPort.ftqTrainMeta.jumpTargetAddress  := ftqBranchMetaRegs(commitFtqId).jumpTargetAddr
  io.bpuFtqPort.ftqTrainMeta.fallThroughAddress := ftqBranchMetaRegs(commitFtqId).fallThroughAddr

  // Bpu meta ram
  // If last cycle accepted p1 input
  bpuMetaWritePort.valid := io.bpuFtqPort.ftqP1.isValid || io.bpuFtqPort.ftqP0.isValid
  bpuMetaWritePort.ptr := Mux(
    io.bpuFtqPort.ftqP1.isValid && !mainBpuRedirectDelay,
    bpuPtr - 1.U,
    bpuPtr
  )
  bpuMetaWritePort.entry := Mux(
    io.bpuFtqPort.ftqP1.isValid,
    io.bpuFtqPort.ftqMeta,
    BpuFtqMetaPort.default
  )

  // P1
  // maintain BPU meta info
  when(bpuMetaWritePort.valid) {
    ftqBpuMetaRegs(bpuMetaWritePort.ptr) := bpuMetaWritePort.entry
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
