import axi.AxiCrossbar
import axi.bundles.AxiMasterPort
import chisel3._
import common.{Pc, RegFile}
import frontend.{InstQueue, SimpleFetchStage}
import control.Cu
import pipeline.dispatch.{IssueStage, RegReadStage, Scoreboard}
import pipeline.execution.ExeStage
import pipeline.writeback.WbStage
import pipeline.mem.MemStage
import spec.Param.isDiffTest
import spec.PipelineStageIndex
import spec.zeroWord
import control.Csr
import spec.Param
import spec.Count

class CoreCpuTop extends Module {
  val io = IO(new Bundle {
    val intrpt = Input(UInt(8.W))
    val axi    = new AxiMasterPort

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
  val instQueue        = Module(new InstQueue)
  val issueStage       = Module(new IssueStage)
  val regReadStage     = Module(new RegReadStage)
  val exeStage         = Module(new ExeStage)
  val memStage         = Module(new MemStage)
  val wbStage          = Module(new WbStage)
  val cu               = Module(new Cu)
  val csr              = Module(new Csr)

  val crossbar = Module(new AxiCrossbar)

  val scoreboard    = Module(new Scoreboard)
  val csrScoreBoard = Module(new Scoreboard(regNum = Count.csrReg))

  val regFile = Module(new RegFile)
  val pc      = Module(new Pc)

  // Default DontCare
  instQueue.io    <> DontCare
  issueStage.io   <> DontCare
  regReadStage.io <> DontCare
  regFile.io      <> DontCare
  scoreboard.io   <> DontCare
  cu.io           <> DontCare
  csr.io          <> DontCare

  // TODO: Other connections
  exeStage.io := DontCare

  // Pc
  pc.io.branchSetPort := exeStage.io.branchSetPort
  pc.io.flushNewPc    := cu.io.newPc

  // AXI top <> AXI crossbar
  crossbar.io.slaves                       <> DontCare
  crossbar.io.masters(0).read.r.bits.user  <> DontCare
  crossbar.io.masters(0).write.b.bits.user <> DontCare
  io.axi.arid                              <> crossbar.io.masters(0).read.ar.bits.id
  io.axi.araddr                            <> crossbar.io.masters(0).read.ar.bits.addr
  io.axi.arlen                             <> crossbar.io.masters(0).read.ar.bits.len
  io.axi.arsize                            <> crossbar.io.masters(0).read.ar.bits.size
  io.axi.arburst                           <> crossbar.io.masters(0).read.ar.bits.burst
  io.axi.arlock                            <> crossbar.io.masters(0).read.ar.bits.lock
  io.axi.arcache                           <> crossbar.io.masters(0).read.ar.bits.cache
  io.axi.arprot                            <> crossbar.io.masters(0).read.ar.bits.prot
  io.axi.arvalid                           <> crossbar.io.masters(0).read.ar.valid
  io.axi.arready                           <> crossbar.io.masters(0).read.ar.ready
  io.axi.rid                               <> crossbar.io.masters(0).read.r.bits.id
  io.axi.rdata                             <> crossbar.io.masters(0).read.r.bits.data
  io.axi.rresp                             <> crossbar.io.masters(0).read.r.bits.resp
  io.axi.rlast                             <> crossbar.io.masters(0).read.r.bits.last
  io.axi.rvalid                            <> crossbar.io.masters(0).read.r.valid
  io.axi.rready                            <> crossbar.io.masters(0).read.r.ready
  io.axi.awid                              <> crossbar.io.masters(0).write.aw.bits.id
  io.axi.awaddr                            <> crossbar.io.masters(0).write.aw.bits.addr
  io.axi.awlen                             <> crossbar.io.masters(0).write.aw.bits.len
  io.axi.awsize                            <> crossbar.io.masters(0).write.aw.bits.size
  io.axi.awburst                           <> crossbar.io.masters(0).write.aw.bits.burst
  io.axi.awlock                            <> crossbar.io.masters(0).write.aw.bits.lock
  io.axi.awcache                           <> crossbar.io.masters(0).write.aw.bits.cache
  io.axi.awprot                            <> crossbar.io.masters(0).write.aw.bits.prot
  io.axi.awvalid                           <> crossbar.io.masters(0).write.aw.valid
  io.axi.awready                           <> crossbar.io.masters(0).write.aw.ready
  io.axi.wid                               <> DontCare
  io.axi.wdata                             <> crossbar.io.masters(0).write.w.bits.data
  io.axi.wstrb                             <> crossbar.io.masters(0).write.w.bits.strb
  io.axi.wlast                             <> crossbar.io.masters(0).write.w.bits.last
  io.axi.wvalid                            <> crossbar.io.masters(0).write.w.valid
  io.axi.wready                            <> crossbar.io.masters(0).write.w.ready
  io.axi.bid                               <> crossbar.io.masters(0).write.b.bits.id
  io.axi.bresp                             <> crossbar.io.masters(0).write.b.bits.resp
  io.axi.bvalid                            <> crossbar.io.masters(0).write.b.valid
  io.axi.bready                            <> crossbar.io.masters(0).write.b.ready

  // `SimpleFetchStage` <> AXI crossbar
  simpleFetchStage.io.axiMasterInterface.arid    <> crossbar.io.slaves(0).read.ar.bits.id
  simpleFetchStage.io.axiMasterInterface.araddr  <> crossbar.io.slaves(0).read.ar.bits.addr
  simpleFetchStage.io.axiMasterInterface.arlen   <> crossbar.io.slaves(0).read.ar.bits.len
  simpleFetchStage.io.axiMasterInterface.arsize  <> crossbar.io.slaves(0).read.ar.bits.size
  simpleFetchStage.io.axiMasterInterface.arburst <> crossbar.io.slaves(0).read.ar.bits.burst
  simpleFetchStage.io.axiMasterInterface.arlock  <> crossbar.io.slaves(0).read.ar.bits.lock
  simpleFetchStage.io.axiMasterInterface.arcache <> crossbar.io.slaves(0).read.ar.bits.cache
  simpleFetchStage.io.axiMasterInterface.arprot  <> crossbar.io.slaves(0).read.ar.bits.prot
  simpleFetchStage.io.axiMasterInterface.arvalid <> crossbar.io.slaves(0).read.ar.valid
  simpleFetchStage.io.axiMasterInterface.arready <> crossbar.io.slaves(0).read.ar.ready
  simpleFetchStage.io.axiMasterInterface.rid     <> crossbar.io.slaves(0).read.r.bits.id
  simpleFetchStage.io.axiMasterInterface.rdata   <> crossbar.io.slaves(0).read.r.bits.data
  simpleFetchStage.io.axiMasterInterface.rresp   <> crossbar.io.slaves(0).read.r.bits.resp
  simpleFetchStage.io.axiMasterInterface.rlast   <> crossbar.io.slaves(0).read.r.bits.last
  simpleFetchStage.io.axiMasterInterface.rvalid  <> crossbar.io.slaves(0).read.r.valid
  simpleFetchStage.io.axiMasterInterface.rready  <> crossbar.io.slaves(0).read.r.ready
  simpleFetchStage.io.axiMasterInterface.awid    <> crossbar.io.slaves(0).write.aw.bits.id
  simpleFetchStage.io.axiMasterInterface.awaddr  <> crossbar.io.slaves(0).write.aw.bits.addr
  simpleFetchStage.io.axiMasterInterface.awlen   <> crossbar.io.slaves(0).write.aw.bits.len
  simpleFetchStage.io.axiMasterInterface.awsize  <> crossbar.io.slaves(0).write.aw.bits.size
  simpleFetchStage.io.axiMasterInterface.awburst <> crossbar.io.slaves(0).write.aw.bits.burst
  simpleFetchStage.io.axiMasterInterface.awlock  <> crossbar.io.slaves(0).write.aw.bits.lock
  simpleFetchStage.io.axiMasterInterface.awcache <> crossbar.io.slaves(0).write.aw.bits.cache
  simpleFetchStage.io.axiMasterInterface.awprot  <> crossbar.io.slaves(0).write.aw.bits.prot
  simpleFetchStage.io.axiMasterInterface.awvalid <> crossbar.io.slaves(0).write.aw.valid
  simpleFetchStage.io.axiMasterInterface.awready <> crossbar.io.slaves(0).write.aw.ready
  simpleFetchStage.io.axiMasterInterface.wid     <> DontCare
  simpleFetchStage.io.axiMasterInterface.wdata   <> crossbar.io.slaves(0).write.w.bits.data
  simpleFetchStage.io.axiMasterInterface.wstrb   <> crossbar.io.slaves(0).write.w.bits.strb
  simpleFetchStage.io.axiMasterInterface.wlast   <> crossbar.io.slaves(0).write.w.bits.last
  simpleFetchStage.io.axiMasterInterface.wvalid  <> crossbar.io.slaves(0).write.w.valid
  simpleFetchStage.io.axiMasterInterface.wready  <> crossbar.io.slaves(0).write.w.ready
  simpleFetchStage.io.axiMasterInterface.bid     <> crossbar.io.slaves(0).write.b.bits.id
  simpleFetchStage.io.axiMasterInterface.bresp   <> crossbar.io.slaves(0).write.b.bits.resp
  simpleFetchStage.io.axiMasterInterface.bvalid  <> crossbar.io.slaves(0).write.b.valid
  simpleFetchStage.io.axiMasterInterface.bready  <> crossbar.io.slaves(0).write.b.ready

  // Simple fetch stage
  instQueue.io.enqueuePort         <> simpleFetchStage.io.instEnqueuePort
  instQueue.io.pipelineControlPort := cu.io.pipelineControlPorts(PipelineStageIndex.instQueue)
  simpleFetchStage.io.pc           := pc.io.pc
  pc.io.isNext                     := simpleFetchStage.io.isPcNext

  // Issue stage
  issueStage.io.fetchInstInfoPort   <> instQueue.io.dequeuePort
  issueStage.io.regScores           := scoreboard.io.regScores
  scoreboard.io.occupyPorts         := issueStage.io.occupyPorts
  issueStage.io.pipelineControlPort := cu.io.pipelineControlPorts(PipelineStageIndex.issueStage)
  issueStage.io.csrRegScores        := csrScoreBoard.io.regScores
  csrScoreBoard.io.occupyPorts      := issueStage.io.csrOccupyPorts

  // Reg-read stage
  regReadStage.io.issuedInfoPort             := issueStage.io.issuedInfoPort
  regReadStage.io.gprReadPorts(0)            <> regFile.io.readPorts(0)
  regReadStage.io.gprReadPorts(1)            <> regFile.io.readPorts(1)
  regReadStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.regReadStage)
  regReadStage.io.instInfoPassThroughPort.in := issueStage.io.instInfoPort

  // Execution stage
  exeStage.io.exeInstPort                := regReadStage.io.exeInstPort
  exeStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.exeStage)
  exeStage.io.instInfoPassThroughPort.in := regReadStage.io.instInfoPassThroughPort.out

  // Mem stage
  memStage.io.gprWritePassThroughPort.in := exeStage.io.gprWritePort
  memStage.io.memLoadStoreInfoPort       := exeStage.io.memLoadStoreInfoPort
  memStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.memStage)
  memStage.io.memLoadStorePort           <> DontCare
  memStage.io.instInfoPassThroughPort.in := exeStage.io.instInfoPassThroughPort.out

  // Write-back stage
  wbStage.io.gprWriteInfoPort           := memStage.io.gprWritePassThroughPort.out
  wbStage.io.instInfoPassThroughPort.in := memStage.io.instInfoPassThroughPort.out
  regFile.io.writePort                  := cu.io.gprWritePassThroughPorts.out(0)
  scoreboard.io.freePorts               := wbStage.io.freePorts
  csrScoreBoard.io.freePorts            := wbStage.io.csrFreePorts

  // Ctrl unit
  cu.io.instInfoPorts(0)               := wbStage.io.instInfoPassThroughPort.out
  cu.io.exeStallRequest                := exeStage.io.stallRequest
  cu.io.memStallRequest                := memStage.io.stallRequest
  cu.io.gprWritePassThroughPorts.in(0) := wbStage.io.gprWritePort
  cu.io.csrValues                      := csr.io.csrValues

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
}
