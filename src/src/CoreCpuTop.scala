import axi.Axi3x1Crossbar
import axi.bundles.AxiMasterInterface
import chisel3._
import common.RegFile
import control.{Csr, StableCounter}
import frontend.Frontend
import memory.{DCache, ICache, Tlb, UncachedAgent}
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
import pipeline.Backend

class CoreCpuTop extends Module {
  val io = IO(new Bundle {
    val intrpt = Input(UInt(8.W))
    val axi    = new AxiMasterInterface

    val debug0_wb = new Bundle {
      val pc = Output(UInt(32.W))
      val rf = new Bundle {
        val wen   = Output(UInt(4.W))
        val wnum  = Output(UInt(5.W))
        val wdata = Output(UInt(32.W))
      }
      val inst = Output(UInt(32.W))
    }

    val diffTest =
      if (isDiffTest)
        Some(Output(new Bundle {
          val cmt_valid_0      = Bool()
          val cmt_valid_1      = Bool()
          val cmt_cnt_inst     = Bool()
          val cmt_timer_64     = UInt(64.W)
          val cmt_inst_ld_en   = UInt(8.W)
          val cmt_ld_paddr     = UInt(32.W)
          val cmt_ld_vaddr     = UInt(32.W)
          val cmt_inst_st_en   = UInt(8.W)
          val cmt_st_paddr     = UInt(32.W)
          val cmt_st_vaddr     = UInt(32.W)
          val cmt_st_data      = UInt(32.W)
          val cmt_csr_rstat_en = Bool()
          val cmt_csr_data     = UInt(32.W)

          val cmt_wen_0   = Bool()
          val cmt_wdest_0 = UInt(8.W)
          val cmt_wdata_0 = UInt(32.W)
          val cmt_pc_0    = UInt(32.W)
          val cmt_inst_0  = UInt(32.W)

          val cmt_wen_1   = Bool()
          val cmt_wdest_1 = UInt(8.W)
          val cmt_wdata_1 = UInt(32.W)
          val cmt_pc_1    = UInt(32.W)
          val cmt_inst_1  = UInt(32.W)

          val cmt_excp_flush = Bool()
          val cmt_ertn       = Bool()
          val cmt_csr_ecode  = UInt(6.W)
          val cmt_tlbfill_en = Bool()
          val cmt_rand_index = UInt(5.W)

          val csr_crmd_diff_0      = UInt(32.W)
          val csr_prmd_diff_0      = UInt(32.W)
          val csr_ectl_diff_0      = UInt(32.W)
          val csr_estat_diff_0     = UInt(32.W)
          val csr_era_diff_0       = UInt(32.W)
          val csr_badv_diff_0      = UInt(32.W)
          val csr_eentry_diff_0    = UInt(32.W)
          val csr_tlbidx_diff_0    = UInt(32.W)
          val csr_tlbehi_diff_0    = UInt(32.W)
          val csr_tlbelo0_diff_0   = UInt(32.W)
          val csr_tlbelo1_diff_0   = UInt(32.W)
          val csr_asid_diff_0      = UInt(32.W)
          val csr_save0_diff_0     = UInt(32.W)
          val csr_save1_diff_0     = UInt(32.W)
          val csr_save2_diff_0     = UInt(32.W)
          val csr_save3_diff_0     = UInt(32.W)
          val csr_tid_diff_0       = UInt(32.W)
          val csr_tcfg_diff_0      = UInt(32.W)
          val csr_tval_diff_0      = UInt(32.W)
          val csr_ticlr_diff_0     = UInt(32.W)
          val csr_llbctl_diff_0    = UInt(32.W)
          val csr_tlbrentry_diff_0 = UInt(32.W)
          val csr_dmw0_diff_0      = UInt(32.W)
          val csr_dmw1_diff_0      = UInt(32.W)
          val csr_pgdl_diff_0      = UInt(32.W)
          val csr_pgdh_diff_0      = UInt(32.W)

          val regs = Vec(32, UInt(32.W))
        }))
      else None
  })

  val iCache      = Module(new ICache)
  val frontend    = Module(new Frontend)
  val backend     = Module(new Backend)
  val cu          = Module(new Cu)
  val csr         = Module(new Csr)
  val commitStage = Module(new CommitStage)
  val regFile     = Module(new RegFile)

  val crossbar = Module(new Axi3x1Crossbar)

  // AXI top <> AXI crossbar
  crossbar.io.master(0) <> io.axi

  // ICache <> AXI crossbar
  crossbar.io.slave(0) <> iCache.io.axiMasterPort

  // Memory related modules
  val dCache        = Module(new DCache)
  val uncachedAgent = Module(new UncachedAgent)
  val tlb           = Option.when(!Param.isNoPrivilege)(Module(new Tlb))

  // Connection for memory related modules
  crossbar.io.slave(1) <> dCache.io.axiMasterPort
  crossbar.io.slave(2) <> uncachedAgent.io.axiMasterPort

  // TLB connection
  tlb.foreach { tlb =>
    tlb.io.csr.in.asId         := csr.io.csrValues.asid
    tlb.io.csr.in.plv          := csr.io.csrValues.crmd.plv
    tlb.io.csr.in.tlbidx       := csr.io.csrValues.tlbidx
    tlb.io.csr.in.tlbehi       := csr.io.csrValues.tlbehi
    tlb.io.csr.in.tlbeloVec(0) := csr.io.csrValues.tlbelo0
    tlb.io.csr.in.tlbeloVec(1) := csr.io.csrValues.tlbelo1
    tlb.io.csr.in.estat        := csr.io.csrValues.estat
    tlb.io.maintenanceInfo     := backend.io.tlbMaintenancePort
    tlb.io.maintenanceTrigger  := backend.io.tlbMaintenanceTrigger
  }

  // Frontend
  //   inst fetch stage
  frontend.io.isFlush    := cu.io.frontendFlush
  frontend.io.ftqFlushId := cu.io.frontendFlushFtqId
  frontend.io.accessPort <> iCache.io.accessPort
  frontend.io.cuNewPc    := cu.io.newPc
  if (isNoPrivilege) {
    frontend.io.tlbTrans <> DontCare
  } else {
    frontend.io.tlbTrans <> tlb.get.io.tlbTransPorts(1)
  }
  frontend.io.csr.crmd   := csr.io.csrValues.crmd
  frontend.io.csr.dmw(0) := csr.io.csrValues.dmw0
  frontend.io.csr.dmw(1) := csr.io.csrValues.dmw1

  // TODO: Connect frontend
  frontend.io.exeFtqPort      <> backend.io.exeFeedBackFtqPort
  frontend.io.cuCommitFtqPort := cu.io.ftqPort
  frontend.io.cuQueryPcBundle <> cu.io.queryPcPort

  // backend
  backend.io.instQueueEnqueuePort <> frontend.io.instDequeuePort

  backend.io.frontendFlush := cu.io.frontendFlush
  backend.io.backendFlush  := cu.io.backendFlush
  backend.io.idleFlush     := cu.io.idleFlush
  backend.io.hasInterrupt  := csr.io.hasInterrupt
  backend.io.isDbarFinish  := cu.io.isDbarFinish

  backend.io.csrValues := csr.io.csrValues

  backend.io.csrReadPort <> csr.io.readPorts.head

  if (isNoPrivilege) {
    backend.io.tlbTransPort <> DontCare
  } else {
    backend.io.tlbTransPort <> tlb.get.io.tlbTransPorts(0)
  }

  backend.io.memReqPeerPort.dCacheReq         <> dCache.io.accessPort.req
  backend.io.memReqPeerPort.uncachedReq       <> uncachedAgent.io.accessPort.req
  backend.io.memReqPeerPort.dCacheMaintenance <> dCache.io.maintenancePort
  backend.io.memReqPeerPort.iCacheMaintenance <> iCache.io.maintenancePort

  backend.io.memResPeerPort.dCacheRes   := dCache.io.accessPort.res
  backend.io.memResPeerPort.uncachedRes := uncachedAgent.io.accessPort.res

  backend.io.tlbDifftestPort.foreach { p =>
    p := tlb.get.io.difftest.get
  }

  regFile.io.writePorts.zip(cu.io.gprWritePassThroughPorts.out).foreach {
    case (dst, src) =>
      dst <> src
  }

  backend.io.regfileDatas.zip(regFile.io.regfileDatas).foreach {
    case (dst, src) =>
      dst := src
  }

  // commit stage
  commitStage.io.ins.zip(backend.io.commitPorts).foreach {
    case (dst, src) =>
      dst <> src
  }

  // Control unit
  cu.io.instInfoPorts.zip(commitStage.io.cuInstInfoPorts).foreach {
    case (dst, src) => dst := src
  }
  cu.io.gprWritePassThroughPorts.in.zip(commitStage.io.gprWritePorts).foreach {
    case (dst, src) => dst := src
  }
  cu.io.csrValues := csr.io.csrValues

  require(Param.jumpBranchPipelineIndex != 0)
  cu.io.branchExe          := backend.io.exeRedirectRequest
  cu.io.redirectFromDecode := backend.io.decodeRedirectRequest

  cu.io.csrFlushRequest   := csr.io.csrFlushRequest
  cu.io.csrWriteInfo      := backend.io.csrWritePort
  cu.io.majorPc           := commitStage.io.majorPc
  cu.io.exceptionVirtAddr := backend.io.exceptionVirtAddr

  // CSR
  csr.io.writePorts.zip(cu.io.csrWritePorts).foreach {
    case (dst, src) =>
      dst := src
  }
  if (isNoPrivilege) {
    csr.io.tlbMaintenanceWritePort.valid := false.B
    csr.io.tlbMaintenanceWritePort.bits  := DontCare
    csr.io.tlbExceptionWritePorts.map(_.valid).foreach(_ := false.B)
    csr.io.tlbExceptionWritePorts.map(_.bits).foreach(_ := DontCare)
  } else {
    csr.io.tlbMaintenanceWritePort.valid := cu.io.tlbMaintenanceCsrWriteValid
    csr.io.tlbMaintenanceWritePort.bits  := tlb.get.io.csr.out
    csr.io.tlbExceptionWritePorts.map(_.valid).zip(cu.io.tlbExceptionCsrWriteValidVec).foreach {
      case (dst, src) =>
        dst := src
    }
    csr.io.tlbExceptionWritePorts.map(_.bits).zip(tlb.get.io.transExceptionCsrPorts).foreach {
      case (dst, src) =>
        dst := src
    }
  }
  csr.io.csrMessage        := cu.io.csrMessage
  csr.io.hardwareInterrupt := io.intrpt

  // Debug ports
  io.debug0_wb.pc   := commitStage.io.ins(0).bits.fetchInfo.pcAddr
  io.debug0_wb.inst := commitStage.io.ins(0).bits.fetchInfo.inst
  io.debug0_wb.rf.wen := VecInit(
    Seq.fill(4)(
      commitStage.io.gprWritePorts(0).en && commitStage.io.ins(0).bits.instInfo.isValid && commitStage.io
        .ins(0)
        .valid && commitStage.io.ins(0).ready && !cu.io.csrMessage.exceptionFlush
    )
  ).asUInt
  io.debug0_wb.rf.wnum  := commitStage.io.gprWritePorts(0).addr
  io.debug0_wb.rf.wdata := commitStage.io.gprWritePorts(0).data

  // pmu
  if (Param.usePmu) {
    val pmu        = Module(new Pmu)
    val backendPmu = backend.io.pmu.get
    pmu.io.instqueueFull      := backendPmu.instqueueFull
    pmu.io.instqueueFullValid := backendPmu.instqueueFullValid
    pmu.io.instQueueEmpty     := backendPmu.instQueueEmpty
    pmu.io.branchInfo         := commitStage.io.pmu_branchInfo.get
    pmu.io.dispatchInfos.zip(backendPmu.dispatchInfos).foreach {
      case (dst, src) =>
        dst := src
    }
    pmu.io.robFull    := backendPmu.robFull
    pmu.io.storeQueue := backendPmu.storeQueue
    pmu.io.dCache     := dCache.io.pmu.get
    pmu.io.iCache     := iCache.io.pmu.get
  }

  // Difftest
  // TODO: Some ports
  (io.diffTest, commitStage.io.difftest) match {
    case (Some(t), Some(w)) =>
      t.cmt_valid_0      := w.valid && !t.cmt_excp_flush
      t.cmt_pc_0         := w.pc
      t.cmt_inst_0       := w.instr
      t.cmt_tlbfill_en   := w.is_TLBFILL
      t.cmt_rand_index   := w.TLBFILL_index
      t.cmt_wen_0        := w.wen
      t.cmt_wdest_0      := w.wdest
      t.cmt_wdata_0      := w.wdata
      t.cmt_csr_rstat_en := w.csr_rstat
      t.cmt_inst_ld_en   := w.ld_en
      t.cmt_ld_vaddr     := w.ld_vaddr
      t.cmt_ld_paddr     := w.ld_paddr
      t.cmt_inst_st_en   := w.st_en
      t.cmt_st_vaddr     := w.st_vaddr
      t.cmt_st_paddr     := w.st_paddr
      t.cmt_st_data      := w.st_data
      t.cmt_cnt_inst     := w.cnt_inst
      t.cmt_timer_64     := w.timer_64

      t.cmt_valid_1 := w.valid_1
      t.cmt_pc_1    := w.pc_1
      t.cmt_inst_1  := w.instr_1
      t.cmt_wen_1   := w.wen_1
      t.cmt_wdest_1 := w.wdest_1
      t.cmt_wdata_1 := w.wdata_1
    case _ =>
  }
  (io.diffTest, cu.io.difftest) match {
    case (Some(t), Some(c)) =>
      t.cmt_ertn       := c.cmt_ertn
      t.cmt_excp_flush := c.cmt_excp_flush
    case _ =>
  }
  (io.diffTest, regFile.io.regfileDatas) match {
    case (Some(t), r) =>
      t.regs := r
    case _ =>
  }
  (io.diffTest, csr.io.csrValues) match {
    case (Some(t), c) =>
      t.csr_crmd_diff_0      := c.crmd.asUInt
      t.csr_prmd_diff_0      := c.prmd.asUInt
      t.csr_ectl_diff_0      := c.ecfg.asUInt
      t.csr_estat_diff_0     := c.estat.asUInt
      t.csr_era_diff_0       := c.era.asUInt
      t.csr_badv_diff_0      := c.badv.asUInt
      t.csr_eentry_diff_0    := c.eentry.asUInt
      t.csr_tlbidx_diff_0    := c.tlbidx.asUInt
      t.csr_tlbehi_diff_0    := c.tlbehi.asUInt
      t.csr_tlbelo0_diff_0   := c.tlbelo0.asUInt
      t.csr_tlbelo1_diff_0   := c.tlbelo1.asUInt
      t.csr_asid_diff_0      := c.asid.asUInt
      t.csr_save0_diff_0     := c.save0.asUInt
      t.csr_save1_diff_0     := c.save1.asUInt
      t.csr_save2_diff_0     := c.save2.asUInt
      t.csr_save3_diff_0     := c.save3.asUInt
      t.csr_tid_diff_0       := c.tid.asUInt
      t.csr_tcfg_diff_0      := c.tcfg.asUInt
      t.csr_tval_diff_0      := c.tval.asUInt
      t.csr_ticlr_diff_0     := c.ticlr.asUInt
      t.csr_llbctl_diff_0    := c.llbctl.asUInt
      t.csr_tlbrentry_diff_0 := c.tlbrentry.asUInt
      t.csr_dmw0_diff_0      := c.dmw0.asUInt
      t.csr_dmw1_diff_0      := c.dmw1.asUInt
      t.csr_pgdl_diff_0      := c.pgdl.asUInt
      t.csr_pgdh_diff_0      := c.pgdh.asUInt

      t.cmt_csr_ecode := c.estat.ecode
      t.cmt_csr_data  := RegNext(c.estat.asUInt, 0.U)
    case _ =>
  }
}
