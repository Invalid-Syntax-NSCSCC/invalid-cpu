package pipeline

import chisel3._
import spec._
import _root_.common.RegFile
import control.{Csr, StableCounter}
import frontend.Frontend
import pipeline.commit.CommitStage
import pipeline.dispatch._
import pipeline.execution._
import pipeline.memory.{AddrTransStage, ExeForMemStage, MemReqStage, MemResStage}
import pipeline.queue.MultiInstQueue
import pipeline.rob.Rob
import spec.Param
import spec.Param.{isDiffTest, isNoPrivilege}
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

class Backend extends Module {

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

    // pmu
    val pmu = Option.when(Param.usePmu)(Output(new Bundle {
      val instqueueFull      = Bool()
      val instqueueFullValid = Bool()
      val instQueueEmpty     = Bool()
      val dispatchInfos      = Vec(Param.pipelineNum, new PmuDispatchBundle)
      val robFull            = Bool()
      val storeQueue         = new PmuStoreQueueNdPort
    }))
  })

  val csrScoreBoard    = Module(new CsrScoreboard)
  val instQueue        = Module(new MultiInstQueue)
  val renameStage      = Module(new RenameStage)
  val dispatchStage    = Module(new DispatchStage)
  val exeForMemStage   = Module(new ExeForMemStage)
  val exePassWbStage_1 = Module(new ExePassWbStage(supportBranchCsr = true))
  val exePassWbStage_2 = Module(new ExePassWbStage(supportBranchCsr = false))
  val exePassWbStages  = Seq(exePassWbStage_1, exePassWbStage_2)
  val rob              = Module(new Rob)
  val stableCounter    = Module(new StableCounter)

  val addrTransStage = Module(new AddrTransStage)
  val memReqStage    = Module(new MemReqStage)
  val memResStage    = Module(new MemResStage)

  io.tlbMaintenancePort    := addrTransStage.io.peer.get.tlbMaintenance
  io.tlbMaintenanceTrigger := rob.io.tlbMaintenanceTrigger

  instQueue.io.enqueuePort     <> io.instQueueEnqueuePort
  instQueue.io.isFrontendFlush := io.frontendFlush
  instQueue.io.isBackendFlush  := io.backendFlush
  instQueue.io.idleBlocking    := io.idleFlush
  instQueue.io.hasInterrupt    := io.hasInterrupt
  io.decodeRedirectRequest     := instQueue.io.redirectRequest

  // rename stage
  renameStage.io.ins.zip(instQueue.io.dequeuePorts).foreach {
    case (dst, src) =>
      dst <> src
  }
  renameStage.io.isFlush := io.backendFlush
  renameStage.io.peer.get.results.zip(rob.io.distributeResults).foreach {
    case (dst, src) =>
      dst := src
  }
  renameStage.io.peer.get.writebacks.zip(rob.io.instWbBroadCasts).foreach {
    case (dst, src) =>
      dst := src
  }

  // dispatch
  dispatchStage.io.ins.zip(renameStage.io.outs).foreach {
    case (dst, src) =>
      dst <> src
  }
  dispatchStage.io.isFlush      := io.backendFlush
  dispatchStage.io.peer.get.plv := io.csrValues.crmd.plv
  dispatchStage.io.peer.get.writebacks.zip(rob.io.instWbBroadCasts).foreach {
    case (dst, src) =>
      dst := src
  }

  // Scoreboards
  csrScoreBoard.io.csrWriteStorePort := exePassWbStage_1.io.peer.get.csrWriteStorePort.get
  csrScoreBoard.io.isFlush           := io.backendFlush

  // Execution stage
  exeForMemStage.io.in                  <> dispatchStage.io.outs(Param.loadStoreIssuePipelineIndex)
  exeForMemStage.io.isFlush             := io.backendFlush
  exeForMemStage.io.peer.get.csr.llbctl := io.csrValues.llbctl
  exeForMemStage.io.peer.get.csr.era    := io.csrValues.era
  exeForMemStage.io.peer.get.dbarFinish := io.isDbarFinish

  exePassWbStage_1.io.peer.get.csrReadPort.get           <> io.csrReadPort
  exePassWbStage_1.io.peer.get.stableCounterReadPort.get <> stableCounter.io
  exePassWbStage_1.io.peer.get.robQueryPcPort.get        <> rob.io.queryPcPort
  io.exeFeedBackFtqPort                                  <> exePassWbStage_1.io.peer.get.feedbackFtq.get
  io.exeRedirectRequest                                  <> exePassWbStage_1.io.peer.get.branchSetPort.get
  assert(Param.loadStoreIssuePipelineIndex == 0)
  exePassWbStages.zipWithIndex.foreach {
    case (exe, idx) =>
      exe.io.in                  <> dispatchStage.io.outs(idx + 1)
      exe.io.isFlush             := io.backendFlush
      exe.io.peer.get.csr.llbctl := io.csrValues.llbctl
      exe.io.peer.get.csr.era    := io.csrValues.era
  }

  // Mem stages
  addrTransStage.io.in      <> exeForMemStage.io.out
  addrTransStage.io.isFlush := io.backendFlush
  addrTransStage.io.peer.foreach { p =>
    p.tlbTrans   <> io.tlbTransPort
    p.csr.dmw(0) := io.csrValues.dmw0
    p.csr.dmw(1) := io.csrValues.dmw1
    p.csr.crmd   := io.csrValues.crmd
  }
  io.exceptionVirtAddr := addrTransStage.io.peer.get.exceptionVirtAddr

  memReqStage.io.isFlush := io.backendFlush
  memReqStage.io.in      <> addrTransStage.io.out
  memReqStage.io.peer.foreach { p =>
    p.dCacheReq         <> io.memReqPeerPort.dCacheReq
    p.uncachedReq       <> io.memReqPeerPort.uncachedReq
    p.iCacheMaintenance <> io.memReqPeerPort.iCacheMaintenance
    p.dCacheMaintenance <> io.memReqPeerPort.dCacheMaintenance
  }

  memResStage.io.isFlush  := io.backendFlush
  memResStage.io.in       <> memReqStage.io.out
  memResStage.io.peer.get <> io.memResPeerPort

  // ROB
  require(Param.loadStoreIssuePipelineIndex == 0)
  rob.io.finishInsts.zipWithIndex.foreach {
    case (dst, idx) =>
      if (idx == Param.loadStoreIssuePipelineIndex) {
        dst <> memResStage.io.out
      } else {
        dst <> exePassWbStages(idx - 1).io.out
      }
  }
  rob.io.requests.zip(renameStage.io.peer.get.requests).foreach {
    case (dst, src) =>
      dst <> src
  }
  rob.io.isFlush     := io.backendFlush
  rob.io.commitStore <> memReqStage.io.peer.get.commitStore
  if (isDiffTest) {
    if (isNoPrivilege) {
      rob.io.tlbDifftest.get.valid     := false.B
      rob.io.tlbDifftest.get.fillIndex := DontCare
    } else {
      rob.io.tlbDifftest.get := io.tlbDifftestPort.get
    }
  }
  rob.io.regfileDatas.zip(io.regfileDatas).foreach {
    case (dst, src) =>
      dst := src
  }

  io.commitPorts.zip(rob.io.commits).foreach {
    case (dst, src) =>
      dst <> src
  }

  io.csrWritePort := csrScoreBoard.io.csrWritePort

  if (Param.usePmu) {
    val pmu = io.pmu.get

    pmu.instqueueFull      := !instQueue.io.enqueuePort.ready
    pmu.instqueueFullValid := instQueue.io.pmu_instqueueFullValid.get
    pmu.instQueueEmpty     := instQueue.io.pmu_instqueueEmpty.get
    pmu.dispatchInfos.zip(dispatchStage.io.peer.get.pmu_dispatchInfos.get).foreach {
      case (dst, src) =>
        dst := src
    }

    pmu.robFull    := !rob.io.requests.head.ready && !io.backendFlush
    pmu.storeQueue := memReqStage.peer.pmu.get
  }

}
