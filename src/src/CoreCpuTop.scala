import axi.bundles.AxiMasterInterface
import axi.Axi3x1Crossbar
import chisel3._
import common.{Pc, RegFile}
import control.{Csr, Cu, StableCounter}
import frontend.SimpleFetchStage
import memory.{DCache, Tlb, UncachedAgent}
import pipeline.dispatch.{BiIssueStage, RegReadStage, Scoreboard}
import pipeline.execution.ExeStage
import pipeline.mem.{AddrTransStage, MemReqStage, MemResStage}
import pipeline.writeback.WbStage
import spec.Param.isDiffTest
import spec.{Count, Param, PipelineStageIndex}
import spec.zeroWord
import pipeline.dispatch.BiIssueStage
import pipeline.queue.BiInstQueue
import control.bundles.PipelineControlNdPort
import chisel3.util.is
import pipeline.rob.bundles.RobIdDistributePort

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
  })

  io <> DontCare

  val simpleFetchStage = Module(new SimpleFetchStage)
  val instQueue        = Module(new BiInstQueue)
  val issueStage       = Module(new BiIssueStage)
  val regReadStage     = Module(new RegReadStage)
  val exeStage         = Module(new ExeStage)
  val wbStage          = Module(new WbStage)
  val cu               = Module(new Cu)
  val csr              = Module(new Csr)
  val stableCounter    = Module(new StableCounter)

  // TODO: Finish mem stages connection
  val addrTransStage = Module(new AddrTransStage)
  val memReqStage    = Module(new MemReqStage)
  val memResStage    = Module(new MemResStage)

  val crossbar = Module(new Axi3x1Crossbar)

  val scoreboard    = Module(new Scoreboard)
  val csrScoreBoard = Module(new Scoreboard(changeNum = Param.csrScoreBoardChangeNum, regNum = Count.csrReg))

  // val dataforward = Module(new DataForwardStage)

  val regFile = Module(new RegFile)
  val pc      = Module(new Pc)

  // Default DontCare
  csr.io      <> DontCare
  crossbar.io <> DontCare // TODO: Fix crossbar

  // PC
  pc.io.newPc := cu.io.newPc

  // AXI top <> AXI crossbar
  crossbar.io.master(0) <> io.axi

  // `SimpleFetchStage` <> AXI crossbar
  crossbar.io.slave(0) <> simpleFetchStage.io.axiMasterInterface

  // Memory related modules
  val dCache        = Module(new DCache)
  val uncachedAgent = Module(new UncachedAgent)
  val tlb           = Module(new Tlb)

  // Connection for memory related modules
  // TODO: Finish TLB maintanence connection
  tlb.io               <> DontCare
  crossbar.io.slave(1) <> dCache.io.axiMasterPort
  crossbar.io.slave(2) <> uncachedAgent.io.axiMasterPort

  // Simple fetch stage
  simpleFetchStage.io.pc                  := pc.io.pc
  simpleFetchStage.io.pipelineControlPort := cu.io.pipelineControlPorts(PipelineStageIndex.fronted)
  pc.io.isNext                            := simpleFetchStage.io.isPcNext

  // Inst Queue
  instQueue.io.enqueuePorts(0) <> simpleFetchStage.io.instEnqueuePort
  // TODO: CONNECT
  instQueue.io.enqueuePorts(1)       <> DontCare // TODO: DELETE
  instQueue.io.enqueuePorts(1).valid := false.B // TODO: DELETE
  instQueue.io.pipelineControlPort   := cu.io.pipelineControlPorts(PipelineStageIndex.fronted)

  // Issue stage
  issueStage.io.fetchInstDecodePorts(0)       <> instQueue.io.dequeuePorts(0)
  issueStage.io.issuedInfoPorts(1).ready      := false.B // TODO: DELETE
  issueStage.io.fetchInstDecodePorts(1)       := DontCare // TODO: DELETE
  issueStage.io.fetchInstDecodePorts(1).valid := false.B // TODO: DELETE
  instQueue.io.dequeuePorts(1).ready          := false.B // TODO: DELETE
  issueStage.io.regScores                     := scoreboard.io.regScores

  issueStage.io.pipelineControlPorts(0) := cu.io.pipelineControlPorts(PipelineStageIndex.issueStage)
  issueStage.io.pipelineControlPorts(1) := PipelineControlNdPort.default // TODO: DELETE
  issueStage.io.csrRegScores            := csrScoreBoard.io.regScores

  issueStage.io.robEmptyNum := 2.U // TODO: DELETE
  issueStage.io.idGetPorts.foreach { port =>
    port.id := 0.U
  } // TODO: DELETE

  // score boards
  scoreboard.io.freePorts(0)    := wbStage.io.freePorts(0)
  csrScoreBoard.io.freePorts(0) := wbStage.io.csrFreePorts(0)
  scoreboard.io.occupyPorts     := issueStage.io.occupyPortss(0)
  csrScoreBoard.io.occupyPorts  := issueStage.io.csrOccupyPortss(0)

  // Reg-read stage
  regReadStage.io.issuedInfoPort      <> issueStage.io.issuedInfoPorts(0)
  regReadStage.io.gprReadPorts(0)     <> regFile.io.readPorts(0)
  regReadStage.io.gprReadPorts(1)     <> regFile.io.readPorts(1)
  regReadStage.io.pipelineControlPort := cu.io.pipelineControlPorts(PipelineStageIndex.regReadStage)

  // regReadStage.io.dataforwardPorts.zip(dataforward.io.readPorts).foreach {
  //   case (regRead, df) => regRead <> df
  // }

  // Execution stage
  exeStage.io.exeInstPort                <> regReadStage.io.exeInstPort
  exeStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.exeStage)
  exeStage.io.instInfoPassThroughPort.in := regReadStage.io.instInfoPort

  exeStage.io.exeResultPort.ready := false.B // TODO: DELETE

  // Mem stages
  // TODO : Ckeck Valid-Ready
  addrTransStage.io.csrPort                    := DontCare
  addrTransStage.io.gprWritePassThroughPort.in := exeStage.io.exeResultPort.bits.gprWritePort
  addrTransStage.io.instInfoPassThroughPort.in := exeStage.io.instInfoPassThroughPort.out
  addrTransStage.io.memAccessPort              := exeStage.io.exeResultPort.bits.memAccessPort
  addrTransStage.io.tlbTransPort               <> tlb.io.tlbTransPorts(0)
  addrTransStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.addrTransStage)
  // TODO: CSR
  memReqStage.io.translatedMemRequestPort   := addrTransStage.io.translatedMemRequestPort
  memReqStage.io.isCachedAccess.in          := addrTransStage.io.isCachedAccess
  memReqStage.io.gprWritePassThroughPort.in := addrTransStage.io.gprWritePassThroughPort.out
  memReqStage.io.instInfoPassThroughPort.in := addrTransStage.io.instInfoPassThroughPort.out
  memReqStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.memReqStage)
  dCache.io.accessPort.req.client           := memReqStage.io.dCacheRequestPort
  uncachedAgent.io.accessPort.req.client    := memReqStage.io.uncachedRequestPort
  dCache.io.accessPort.req.isReady          <> DontCare // Assume ready guaranteed by in-order access
  uncachedAgent.io.accessPort.req.isReady   <> DontCare // Assume ready guaranteed by in-order access
  memResStage.io.isHasRequest               := memReqStage.io.isHasRequest
  memResStage.io.isCachedRequest            := memReqStage.io.isCachedAccess.out
  memResStage.io.isUnsigned                 := memReqStage.io.isUnsigned
  memResStage.io.dataMask                   := memReqStage.io.dataMask
  memResStage.io.gprWritePassThroughPort.in := addrTransStage.io.gprWritePassThroughPort.out
  memResStage.io.instInfoPassThroughPort.in := addrTransStage.io.instInfoPassThroughPort.out
  memResStage.io.dCacheResponsePort         := dCache.io.accessPort.res
  memResStage.io.uncachedResponsePort       := uncachedAgent.io.accessPort.res
  memResStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.memResStage)

  // Write-back stage
  wbStage.io.gprWriteInfoPort           := memResStage.io.gprWritePassThroughPort.out
  wbStage.io.instInfoPassThroughPort.in := memResStage.io.instInfoPassThroughPort.out
  regFile.io.writePort                  := cu.io.gprWritePassThroughPorts.out(0)

  // data forward
  // dataforward.io.writePorts(0) := exeStage.io.gprWritePort
  // dataforward.io.writePorts(1) := memStage.io.gprWritePassThroughPort.out

  // Ctrl unit
  cu.io.instInfoPorts(0)               := wbStage.io.instInfoPassThroughPort.out
  cu.io.exeStallRequest                := false.B // TODO : DELETE
  cu.io.memResStallRequest             := memResStage.io.stallRequest
  cu.io.gprWritePassThroughPorts.in(0) := wbStage.io.gprWritePort
  cu.io.csrValues                      := csr.io.csrValues
  cu.io.stableCounterReadPort          <> stableCounter.io
  cu.io.jumpPc                         := exeStage.io.branchSetPort

  // Csr
  csr.io.writePorts.zip(cu.io.csrWritePorts).foreach {
    case (dst, src) =>
      dst := src
  }
  csr.io.csrMessage := cu.io.csrMessage
  csr.io.readPorts  <> regReadStage.io.csrReadPorts

  // Debug ports
  io.debug0_wb.pc       := wbStage.io.instInfoPassThroughPort.out.pc
  io.debug0_wb.rf.wen   := wbStage.io.gprWritePort.en
  io.debug0_wb.rf.wnum  := wbStage.io.gprWritePort.addr
  io.debug0_wb.rf.wdata := wbStage.io.gprWritePort.data
  io.debug0_wb.inst     := wbStage.io.instInfoPassThroughPort.out.inst

  // Difftest
  // TODO: DifftestInstrCommit (partial), DifftestExcpEvent, DifftestTrapEvent, DifftestStoreEvent, DifftestLoadEvent, DifftestCSRRegState
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
