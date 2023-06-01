import axi.bundles.AxiMasterInterface
import axi.Axi3x1Crossbar
import chisel3._
import common.{Pc, RegFile}
import control.{Csr, Cu, StableCounter}
import frontend.{Frontend, InstFetchStage, NaiiveFetchStage, SimpleFetchStage}
import memory.{DCache, Tlb, UncachedAgent}
import pipeline.dispatch.{RegReadNdPort, RegReadStage, Scoreboard}
import pipeline.dispatch.BiIssueStage

import pipeline.execution.ExeStage
import pipeline.mem.{AddrTransStage, MemReqStage, MemResStage}
import pipeline.writeback.WbStage
import spec.Param.isDiffTest
import spec.{Count, Param, PipelineStageIndex}
import spec.zeroWord
import pipeline.queue.BiInstQueue
import control.bundles.PipelineControlNdPort
import chisel3.util.is
import pipeline.rob.bundles.RobIdDistributePort
import memory.ICache
import frontend.Frontend
import pipeline.dispatch.bundles.ScoreboardChangeNdPort

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
          val cmt_valid        = Bool()
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

          val cmt_wen   = Bool()
          val cmt_wdest = UInt(8.W)
          val cmt_wdata = UInt(32.W)
          val cmt_pc    = UInt(32.W)
          val cmt_inst  = UInt(32.W)

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

    val issuedInfoPort   = Output(new RegReadNdPort)
    val issueOutputValid = Output(Bool())
  })

  io <> DontCare
  val iCache     = Module(new ICache)
  val frontend   = Module(new Frontend)
  val instQueue  = Module(new BiInstQueue)
  val issueStage = Module(new BiIssueStage)
  io.issuedInfoPort   := issueStage.io.outs(0).bits
  io.issueOutputValid := issueStage.io.outs(0).valid
  val regReadStage  = Module(new RegReadStage)
  val exeStage      = Module(new ExeStage)
  val wbStage       = Module(new WbStage)
  val cu            = Module(new Cu)
  val csr           = Module(new Csr)
  val stableCounter = Module(new StableCounter)

  // TODO: Finish mem stages connection
  val addrTransStage = Module(new AddrTransStage)
  val memReqStage    = Module(new MemReqStage)
  val memResStage    = Module(new MemResStage)

  val crossbar = Module(new Axi3x1Crossbar)

  val scoreboard    = Module(new Scoreboard)
  val csrScoreBoard = Module(new Scoreboard(regNum = Count.csrReg))

  // val dataforward = Module(new DataForwardStage)

  val regFile = Module(new RegFile)
  val pc      = Module(new Pc)

  // Default DontCare
  csr.io <> DontCare

  // PC
  pc.io.newPc := cu.io.newPc

  // AXI top <> AXI crossbar
  crossbar.io.master(0) <> io.axi

  // `ICache` <> AXI crossbar
  crossbar.io.slave(0) <> iCache.io.axiMasterPort

  // Memory related modules
  val dCache        = Module(new DCache)
  val uncachedAgent = Module(new UncachedAgent)
  val tlb           = Module(new Tlb)

  // Connection for memory related modules
  // TODO: Finish TLB maintanence connection
  tlb.io               <> DontCare
  crossbar.io.slave(1) <> dCache.io.axiMasterPort
  crossbar.io.slave(2) <> uncachedAgent.io.axiMasterPort

  // TLB connection
  // TODO: Maintenance and CSR write
  tlb.io.csr.in.asId        := csr.io.csrValues.asid
  tlb.io.csr.in.plv         := csr.io.csrValues.crmd.plv
  tlb.io.csr.in.tlbidx      := csr.io.csrValues.tlbidx
  tlb.io.csr.in.tlbehi      := csr.io.csrValues.tlbehi
  tlb.io.csr.in.tlbloVec(0) := csr.io.csrValues.tlbelo0
  tlb.io.csr.in.tlbloVec(1) := csr.io.csrValues.tlbelo1

  // Frontend
  //   inst fetch stage
  frontend.io.iCacheAccessPort <> iCache.io.iCacheAccessPort
  frontend.io.pc               := pc.io.pc
  frontend.io.isFlush          := cu.io.flushes(PipelineStageIndex.frontend)
  pc.io.isNext                 := frontend.io.isPcNext

  // Instruction queue
  instQueue.io.enqueuePorts(0) <> frontend.io.instEnqueuePort

  // TODO: CONNECT
  instQueue.io.enqueuePorts(1)       <> DontCare // TODO: Connect Second Pipeline
  instQueue.io.enqueuePorts(1).valid := false.B // TODO: Connect Second Pipeline
  instQueue.io.isFlush               := cu.io.flushes(PipelineStageIndex.frontend)

  // Issue stage
  issueStage.io.ins(0)               <> instQueue.io.dequeuePorts(0)
  issueStage.io.outs(1).ready        := false.B // TODO: Connect Second Pipeline
  issueStage.io.ins(1)               := DontCare // TODO: Connect Second Pipeline
  issueStage.io.ins(1).valid         := false.B // TODO: Connect Second Pipeline
  instQueue.io.dequeuePorts(1).ready := false.B // TODO: Connect Second Pipeline
  issueStage.io.peer.get.regScores   := scoreboard.io.regScores

  issueStage.io.isFlush               := cu.io.flushes(PipelineStageIndex.issueStage)
  issueStage.io.peer.get.csrRegScores := csrScoreBoard.io.regScores

  issueStage.io.peer.get.robEmptyNum := 2.U // TODO: Connect Second Pipeline
  issueStage.io.peer.get.idGetPorts.foreach { port =>
    port.id := 0.U
  } // TODO: Connect Second Pipeline

  // Scoreboards
  scoreboard.io.freePorts(0)      := wbStage.io.freePort
  csrScoreBoard.io.freePorts(0)   := wbStage.io.csrFreePort
  scoreboard.io.freePorts(1)      := ScoreboardChangeNdPort.default // TODO: Connect Second Pipeline
  csrScoreBoard.io.freePorts(1)   := ScoreboardChangeNdPort.default // TODO: Connect Second Pipeline
  scoreboard.io.toMemPorts(0)     := exeStage.io.peer.get.scoreboardChangePort
  scoreboard.io.toMemPorts(1)     := ScoreboardChangeNdPort.default // TODO: Connect Second Pipeline
  csrScoreBoard.io.toMemPorts(0)  := exeStage.io.peer.get.csrScoreboardChangePort
  csrScoreBoard.io.toMemPorts(1)  := ScoreboardChangeNdPort.default // TODO: Connect Second Pipeline
  scoreboard.io.occupyPorts(0)    := issueStage.io.peer.get.occupyPortss(0)(0)
  csrScoreBoard.io.occupyPorts(0) := issueStage.io.peer.get.csrOccupyPortss(0)(0)
  scoreboard.io.occupyPorts(1)    := ScoreboardChangeNdPort.default // TODO: Connect Second Pipeline
  csrScoreBoard.io.occupyPorts(1) := ScoreboardChangeNdPort.default // TODO: Connect Second Pipeline
  scoreboard.io.isFlush           := cu.io.flushes(PipelineStageIndex.scoreboard)
  csrScoreBoard.io.isFlush        := cu.io.flushes(PipelineStageIndex.scoreboard)
  scoreboard.io.branchFlush       := cu.io.branchScoreboardFlush
  csrScoreBoard.io.branchFlush    := cu.io.branchScoreboardFlush

  // Reg-read stage
  regReadStage.io.in <> issueStage.io.outs(0)
  regReadStage.io.peer.get.gprReadPorts.zip(regFile.io.readPorts).foreach {
    case (stage, rf) =>
      stage <> rf
  }
  regReadStage.io.peer.get.csrReadPorts(0) <> csr.io.readPorts(0)
  regReadStage.io.isFlush                  := cu.io.flushes(PipelineStageIndex.regReadStage)

  // Execution stage
  exeStage.io.in      <> regReadStage.io.out
  exeStage.io.isFlush := cu.io.flushes(PipelineStageIndex.exeStage)
  exeStage.io.peer.foreach { p =>
    p.csr.llbctl := csr.io.csrValues.llbctl
  }

  // Mem stages
  addrTransStage.io.in      <> exeStage.io.out
  addrTransStage.io.isFlush := cu.io.flushes(PipelineStageIndex.addrTransStage)
  addrTransStage.io.peer.foreach { p =>
    p.tlbTrans   <> tlb.io.tlbTransPorts(0)
    p.csr.dmw(0) := csr.io.csrValues.dmw0
    p.csr.dmw(1) := csr.io.csrValues.dmw1
    p.csr.crmd   := csr.io.csrValues.crmd
  }

  memReqStage.io.isFlush := cu.io.flushes(PipelineStageIndex.memReqStage)
  memReqStage.io.in      <> addrTransStage.io.out
  memReqStage.io.peer.foreach { p =>
    p.dCacheReq   <> dCache.io.accessPort.req
    p.uncachedReq <> uncachedAgent.io.accessPort.req
  }

  memResStage.io.isFlush := cu.io.flushes(PipelineStageIndex.memResStage)
  memResStage.io.in      <> memReqStage.io.out
  memResStage.io.peer.foreach { p =>
    p.dCacheRes   := dCache.io.accessPort.res
    p.uncachedRes := uncachedAgent.io.accessPort.res
  }

  // Write-back stage
  wbStage.io.in        <> memResStage.io.out
  regFile.io.writePort := cu.io.gprWritePassThroughPorts.out(0)

  // Ctrl unit
  cu.io.instInfoPorts(0)               := wbStage.io.cuInstInfoPort
  cu.io.gprWritePassThroughPorts.in(0) := wbStage.io.gprWritePort
  cu.io.csrValues                      := csr.io.csrValues
  cu.io.stableCounterReadPort          <> stableCounter.io
  cu.io.jumpPc                         := exeStage.io.peer.get.branchSetPort

  // After memory request flush connection
  memReqStage.io.peer.get.isAfterMemReqFlush := cu.io.isAfterMemReqFlush
  cu.io.isExceptionValidVec(0)               := memReqStage.io.peer.get.isExceptionValid
  cu.io.isExceptionValidVec(1)               := memResStage.io.peer.get.isExceptionValid
  cu.io.isExceptionValidVec(2)               := wbStage.io.isExceptionValid

  // Csr
  csr.io.writePorts.zip(cu.io.csrWritePorts).foreach {
    case (dst, src) =>
      dst := src
  }
  csr.io.csrMessage := cu.io.csrMessage

  // Debug ports
  io.debug0_wb.pc       := wbStage.io.in.bits.instInfo.pc
  io.debug0_wb.inst     := wbStage.io.in.bits.instInfo.inst
  io.debug0_wb.rf.wen   := wbStage.io.gprWritePort.en
  io.debug0_wb.rf.wnum  := wbStage.io.gprWritePort.addr
  io.debug0_wb.rf.wdata := wbStage.io.gprWritePort.data

  // Difftest
  // TODO: Some ports
  (io.diffTest, wbStage.io.difftest) match {
    case (Some(t), Some(w)) =>
      t.cmt_valid        := w.valid
      t.cmt_pc           := w.pc
      t.cmt_inst         := w.instr
      t.cmt_tlbfill_en   := w.is_TLBFILL
      t.cmt_rand_index   := w.TLBFILL_index
      t.cmt_cnt_inst     := w.is_CNTinst
      t.cmt_timer_64     := w.timer_64_value
      t.cmt_wen          := w.wen
      t.cmt_wdest        := w.wdest
      t.cmt_wdata        := w.wdata
      t.cmt_csr_rstat_en := w.csr_rstat
      t.cmt_csr_data     := w.csr_data
      t.cmt_inst_ld_en   := w.ld_en
      t.cmt_ld_vaddr     := w.ld_vaddr
      t.cmt_ld_paddr     := w.ld_paddr
      t.cmt_inst_st_en   := w.st_en
      t.cmt_st_vaddr     := w.st_vaddr
      t.cmt_st_paddr     := w.st_paddr
      t.cmt_st_data      := w.st_data
    case _ =>
  }
  (io.diffTest, cu.io.difftest) match {
    case (Some(t), Some(c)) =>
      t.cmt_ertn       := c.cmt_ertn
      t.cmt_excp_flush := c.cmt_excp_flush
    case _ =>
  }
  (io.diffTest, regFile.io.difftest) match {
    case (Some(t), Some(r)) =>
      t.regs := r.gpr
    case _ =>
  }
  (io.diffTest, csr.io.csrValues) match {
    case (Some(t), c) =>
      t.csr_crmd_diff_0      := c.crmd.asUInt
      t.csr_prmd_diff_0      := c.prmd.asUInt
      t.csr_ectl_diff_0      := zeroWord // TODO: 删除 ?
      t.csr_estat_diff_0     := c.estat.asUInt.asUInt
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
    case _ =>
  }
}
