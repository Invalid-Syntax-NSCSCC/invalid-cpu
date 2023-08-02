package newpipeline

import chisel3._
import chisel3.util._
import spec._
import spec.Param
import control.Cu
import pmu.Pmu
import pmu.bundles.PmuNdPort
import spec.ExeInst
import _root_.memory.bundles.TlbMaintenanceNdPort
import chisel3.util.Decoupled
import frontend.bundles.ExeFtqPort
import pipeline.queue.InstQueueEnqNdPort
import control.bundles.CsrValuePort
import control.bundles.CsrReadPort
import _root_.memory.bundles.TlbTransPort
import pipeline.memory.MemReqPeerPort
import pipeline.memory.MemResPeerPort
import pipeline.commit.bundles.DifftestTlbFillNdPort
import _root_.common.bundles.RfWriteNdPort
import pipeline.commit.CommitNdPort
import _root_.common.bundles.BackendRedirectPcNdPort
import control.bundles.CsrWriteNdPort
import pmu.bundles.PmuDispatchBundle
import pmu.bundles.PmuStoreQueueNdPort
import _root_.memory.bundles.MemRequestHandshakePort
import _root_.memory.bundles.CacheMaintenanceHandshakePort
import pipeline.dispatch.CsrScoreboard
import newpipeline.queue.NewInstQueue
import newpipeline.dispatch.NewDispatchStage
import newpipeline.execution.MainExeStage
import newpipeline.execution.SimpleExeStage
import newpipeline.rob.NewRob
import control.StableCounter
import newpipeline.memory.LoadStoreQueue

class NewBackend extends Module {
  val io = IO(new Bundle {

    val regfileDatas = Input(Vec(Count.reg, UInt(Width.Reg.data)))

    val decodeRedirectRequest = Output(new BackendRedirectPcNdPort)
    val exeRedirectRequest    = Output(new BackendRedirectPcNdPort)
    val csrWritePort          = Output(new CsrWriteNdPort)
    val exceptionVirtAddr     = Output(UInt(Width.Reg.data))

    val tlbMaintenancePort    = Output(new TlbMaintenanceNdPort)
    val tlbMaintenanceTrigger = Output(Bool())

    val exeFeedBackFtqPort = Flipped(new ExeFtqPort)

    val instQueueEnqueuePort = Flipped(Decoupled(new InstQueueEnqNdPort))

    val frontendFlush = Input(Bool())
    val backendFlush  = Input(Bool())
    val idleFlush     = Input(Bool())
    val hasInterrupt  = Input(Bool())
    val isDbarFinish  = Input(Bool())

    val csrReadPort = Flipped(new CsrReadPort)
    val csrValues   = Input(new CsrValuePort)

    val tlbTransPort = Flipped(new TlbTransPort)
    val memReqPeerPort = new Bundle {
      val dCacheReq         = Flipped(new MemRequestHandshakePort)
      val uncachedReq       = Flipped(new MemRequestHandshakePort)
      val dCacheMaintenance = Flipped(new CacheMaintenanceHandshakePort)
      val iCacheMaintenance = Flipped(new CacheMaintenanceHandshakePort)
    }
    val memResPeerPort = new MemResPeerPort

    val tlbDifftestPort = Option.when(Param.isDiffTest && !Param.isNoPrivilege)(Input(new DifftestTlbFillNdPort))

    val commitPorts = Vec(Param.commitNum, Decoupled(new CommitNdPort))

    // // pmu
    // val pmu = Option.when(Param.usePmu)(Output(new Bundle {
    //   val instqueueFull      = Bool()
    //   val instqueueFullValid = Bool()
    //   val instQueueEmpty     = Bool()
    //   val dispatchInfos      = Vec(Param.pipelineNum, new PmuDispatchBundle)
    //   val robFull            = Bool()
    //   val storeQueue         = new PmuStoreQueueNdPort
    // }))
  })

  def connectVec[T <: Data](a: Vec[T], b: Vec[T]): Unit = {
    a.zip(b).foreach {
      case (l, r) =>
        l <> r
    }
  }

  def connectVec2[T <: Data](a: Vec[Vec[T]], b: Vec[Vec[T]]): Unit = {
    a.zip(b).foreach {
      case (l, r) =>
        connectVec(l, r)
    }
  }

  val csrScoreBoard   = Module(new CsrScoreboard)
  val instQueue       = Module(new NewInstQueue)
  val dispatch        = Module(new NewDispatchStage)
  val mainExeStage    = Module(new MainExeStage)
  val simpleExeStages = Seq.fill(Param.pipelineNum - 1)(Module(new SimpleExeStage))
  val rob             = Module(new NewRob)
  val stableCounter   = Module(new StableCounter)

  val loadStoreQueue = Module(new LoadStoreQueue)

  // csr score board
  io.csrWritePort                    := csrScoreBoard.io.csrWritePort
  csrScoreBoard.io.csrWriteStorePort := mainExeStage.io.peer.get.csrWriteStorePort

  // inst queue
  io.decodeRedirectRequest     := instQueue.io.redirectRequest
  instQueue.io.enqueuePort     <> io.instQueueEnqueuePort
  instQueue.io.isFrontendFlush := io.frontendFlush
  instQueue.io.isBackendFlush  := io.backendFlush
  instQueue.io.idleBlocking    := io.idleFlush
  instQueue.io.hasInterrupt    := io.hasInterrupt
  instQueue.io.plv             := io.csrValues.crmd.plv
  connectVec(instQueue.io.robIdRequests, rob.io.requests)

  // dispatch
  dispatch.io.isFlush := io.backendFlush
  connectVec(dispatch.io.ins, instQueue.io.dequeuePorts)
  connectVec(dispatch.io.peer.get.occupyPorts, rob.io.occupyPorts)
  connectVec2(dispatch.io.peer.get.regReadPorts, rob.io.regReadPorts)

  // exe
  val exePeer = mainExeStage.io.peer.get
  exePeer.csr.llbctl            := io.csrValues.llbctl
  exePeer.csr.era               := io.csrValues.era
  exePeer.dbarFinish            := io.isDbarFinish
  io.exeRedirectRequest         := exePeer.branchSetPort
  exePeer.stableCounterReadPort <> stableCounter.io
  exePeer.csrReadPort           <> io.csrReadPort
  exePeer.feedbackFtq           <> io.exeFeedBackFtqPort
  exePeer.robQueryPcPort        <> rob.io.queryPcPort

  (Seq(mainExeStage) ++ simpleExeStages).zip(dispatch.io.outs).foreach {
    case (dst, src) =>
      dst.io.in      <> src
      dst.io.isFlush := io.backendFlush
  }

  mainExeStage.io.out.ready := loadStoreQueue.io.in.ready
  simpleExeStages.foreach { exe =>
    exe.io.out.ready := true.B
  }

  // load store queue
  loadStoreQueue.io.in.valid := mainExeStage.io.out.valid && mainExeStage.io.out.bits.isLoadStore
  loadStoreQueue.io.in.bits  := mainExeStage.io.out.bits
  loadStoreQueue.io.isFlush  := io.backendFlush

  // rob
  connectVec(rob.io.regfileDatas, io.regfileDatas)
  connectVec(io.commitPorts, rob.io.commits)
  rob.io.isFlush := io.backendFlush
  rob.io.finishInsts.take(Param.pipelineNum - 1).zip(simpleExeStages).foreach {
    case (dst, src) =>
      dst <> src.io.out
  }
  val robMainExeWbPort = rob.io.finishInsts(Param.pipelineNum - 1)
  robMainExeWbPort.valid := mainExeStage.io.out.valid && !mainExeStage.io.out.bits.isLoadStore
  robMainExeWbPort.bits  := mainExeStage.io.out.bits.wb

  val robLsuWbPort = rob.io.finishInsts(Param.pipelineNum)
  robLsuWbPort <> DontCare // TODO: connenct mem res

  if (Param.isDiffTest) {
    if (Param.isNoPrivilege) {
      rob.io.tlbDifftest.get.valid     := false.B
      rob.io.tlbDifftest.get.fillIndex := DontCare
    } else {
      rob.io.tlbDifftest.get := io.tlbDifftestPort.get
    }
  }
}
