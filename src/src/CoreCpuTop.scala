import axi.axi_3x1_crossbar
import axi.bundles.AxiMasterPort
import chisel3._
import chisel3.internal.sourceinfo.MemTransform
import common.{Pc, RegFile}
import frontend.{InstQueue, SimpleFetchStage}
import control.Cu
import pipeline.dataforward.DataForwardStage
import pipeline.dispatch.{IssueStage, RegReadStage, Scoreboard}
import pipeline.execution.ExeStage
import pipeline.writeback.WbStage
import pipeline.mem.{AddrTransStage, MemReqStage, MemResStage}
import spec.Param.isDiffTest
import spec.PipelineStageIndex
import spec.zeroWord
import control.Csr
import spec.Param
import spec.Count
import control.StableCounter
import memory.{DCache, Tlb, UncachedAgent}
import spec.ExeInst

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
  val wbStage          = Module(new WbStage)
  val cu               = Module(new Cu)
  val csr              = Module(new Csr)
  val stableCounter    = Module(new StableCounter)

  // TODO: Finish mem stages connection
  val addrTransStage = Module(new AddrTransStage)
  val memReqStage    = Module(new MemReqStage)
  val memResStage    = Module(new MemResStage)

  val crossbar = Module(new axi_3x1_crossbar)

  val scoreboard    = Module(new Scoreboard)
  val csrScoreBoard = Module(new Scoreboard(changeNum = Param.csrScoreBoardChangeNum, regNum = Count.csrReg))

  // val dataforward = Module(new DataForwardStage)

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

  // Pc
  pc.io.newPc := cu.io.newPc

  // TODO: debug crossbar
//  io.axi      <> simpleFetchStage.io.axiMasterInterface
//  crossbar.io <> DontCare
  crossbar.io.clk <> clock
  crossbar.io.rst <> reset
  // AXI top <> AXI crossbar
  crossbar.io.m00_axi_arid    <> io.axi.arid
  crossbar.io.m00_axi_araddr  <> io.axi.araddr
  crossbar.io.m00_axi_arlen   <> io.axi.arlen
  crossbar.io.m00_axi_arsize  <> io.axi.arsize
  crossbar.io.m00_axi_arburst <> io.axi.arburst
  crossbar.io.m00_axi_arlock  <> io.axi.arlock
  crossbar.io.m00_axi_arcache <> io.axi.arcache
  crossbar.io.m00_axi_arprot  <> io.axi.arprot
  crossbar.io.m00_axi_arvalid <> io.axi.arvalid
  crossbar.io.m00_axi_arready <> io.axi.arready
  crossbar.io.m00_axi_rid     <> io.axi.rid
  crossbar.io.m00_axi_rdata   <> io.axi.rdata
  crossbar.io.m00_axi_rresp   <> io.axi.rresp
  crossbar.io.m00_axi_rlast   <> io.axi.rlast
  crossbar.io.m00_axi_ruser   <> DontCare
  crossbar.io.m00_axi_rvalid  <> io.axi.rvalid
  crossbar.io.m00_axi_rready  <> io.axi.rready
  crossbar.io.m00_axi_awid    <> io.axi.awid
  crossbar.io.m00_axi_awaddr  <> io.axi.awaddr
  crossbar.io.m00_axi_awlen   <> io.axi.awlen
  crossbar.io.m00_axi_awsize  <> io.axi.awsize
  crossbar.io.m00_axi_awburst <> io.axi.awburst
  crossbar.io.m00_axi_awlock  <> io.axi.awlock
  crossbar.io.m00_axi_awcache <> io.axi.awcache
  crossbar.io.m00_axi_awprot  <> io.axi.awprot
  crossbar.io.m00_axi_awvalid <> io.axi.awvalid
  crossbar.io.m00_axi_awready <> io.axi.awready
  crossbar.io.m00_axi_wdata   <> io.axi.wdata
  crossbar.io.m00_axi_wstrb   <> io.axi.wstrb
  crossbar.io.m00_axi_wlast   <> io.axi.wlast
  crossbar.io.m00_axi_wuser   <> DontCare
  crossbar.io.m00_axi_wvalid  <> io.axi.wvalid
  crossbar.io.m00_axi_wready  <> io.axi.wready
  crossbar.io.m00_axi_bid     <> io.axi.bid
  crossbar.io.m00_axi_bresp   <> io.axi.bresp
  crossbar.io.m00_axi_buser   <> DontCare
  crossbar.io.m00_axi_bvalid  <> io.axi.bvalid
  crossbar.io.m00_axi_bready  <> io.axi.bready

  // `SimpleFetchStage` <> AXI crossbar
  simpleFetchStage.io.axiMasterInterface.arid    <> io.axi.arid
  simpleFetchStage.io.axiMasterInterface.araddr  <> io.axi.araddr
  simpleFetchStage.io.axiMasterInterface.arlen   <> io.axi.arlen
  simpleFetchStage.io.axiMasterInterface.arsize  <> io.axi.arsize
  simpleFetchStage.io.axiMasterInterface.arburst <> io.axi.arburst
  simpleFetchStage.io.axiMasterInterface.arlock  <> io.axi.arlock
  simpleFetchStage.io.axiMasterInterface.arcache <> io.axi.arcache
  simpleFetchStage.io.axiMasterInterface.arprot  <> io.axi.arprot
  simpleFetchStage.io.axiMasterInterface.arvalid <> io.axi.arvalid
  simpleFetchStage.io.axiMasterInterface.arready <> io.axi.arready
  simpleFetchStage.io.axiMasterInterface.rid     <> io.axi.rid
  simpleFetchStage.io.axiMasterInterface.rdata   <> io.axi.rdata
  simpleFetchStage.io.axiMasterInterface.rresp   <> io.axi.rresp
  simpleFetchStage.io.axiMasterInterface.rlast   <> io.axi.rlast
  simpleFetchStage.io.axiMasterInterface.rvalid  <> io.axi.rvalid
  simpleFetchStage.io.axiMasterInterface.rready  <> io.axi.rready
  simpleFetchStage.io.axiMasterInterface.awid    <> io.axi.awid
  simpleFetchStage.io.axiMasterInterface.awaddr  <> io.axi.awaddr
  simpleFetchStage.io.axiMasterInterface.awlen   <> io.axi.awlen
  simpleFetchStage.io.axiMasterInterface.awsize  <> io.axi.awsize
  simpleFetchStage.io.axiMasterInterface.awburst <> io.axi.awburst
  simpleFetchStage.io.axiMasterInterface.awlock  <> io.axi.awlock
  simpleFetchStage.io.axiMasterInterface.awcache <> io.axi.awcache
  simpleFetchStage.io.axiMasterInterface.awprot  <> io.axi.awprot
  simpleFetchStage.io.axiMasterInterface.awvalid <> io.axi.awvalid
  simpleFetchStage.io.axiMasterInterface.awready <> io.axi.awready
  simpleFetchStage.io.axiMasterInterface.wdata   <> io.axi.wdata
  simpleFetchStage.io.axiMasterInterface.wstrb   <> io.axi.wstrb
  simpleFetchStage.io.axiMasterInterface.wlast   <> io.axi.wlast
  simpleFetchStage.io.axiMasterInterface.wid     <> io.axi.wid
  simpleFetchStage.io.axiMasterInterface.wvalid  <> io.axi.wvalid
  simpleFetchStage.io.axiMasterInterface.wready  <> io.axi.wready
  simpleFetchStage.io.axiMasterInterface.bid     <> io.axi.bid
  simpleFetchStage.io.axiMasterInterface.bresp   <> io.axi.bresp
  simpleFetchStage.io.axiMasterInterface.bvalid  <> io.axi.bvalid
  simpleFetchStage.io.axiMasterInterface.bready  <> io.axi.bready
  crossbar.io.s00_axi_arid                       <> DontCare
  crossbar.io.s00_axi_araddr                     <> DontCare
  crossbar.io.s00_axi_arlen                      <> DontCare
  crossbar.io.s00_axi_arsize                     <> DontCare
  crossbar.io.s00_axi_arburst                    <> DontCare
  crossbar.io.s00_axi_arlock                     <> DontCare
  crossbar.io.s00_axi_arcache                    <> DontCare
  crossbar.io.s00_axi_arprot                     <> DontCare
  crossbar.io.s00_axi_arqos                      <> DontCare
  crossbar.io.s00_axi_aruser                     <> DontCare
  crossbar.io.s00_axi_arvalid                    <> DontCare
  crossbar.io.s00_axi_arready                    <> DontCare
  crossbar.io.s00_axi_rid                        <> DontCare
  crossbar.io.s00_axi_rdata                      <> DontCare
  crossbar.io.s00_axi_rresp                      <> DontCare
  crossbar.io.s00_axi_rlast                      <> DontCare
  crossbar.io.s00_axi_ruser                      <> DontCare
  crossbar.io.s00_axi_rvalid                     <> DontCare
  crossbar.io.s00_axi_rready                     <> DontCare
  crossbar.io.s00_axi_awid                       <> DontCare
  crossbar.io.s00_axi_awaddr                     <> DontCare
  crossbar.io.s00_axi_awlen                      <> DontCare
  crossbar.io.s00_axi_awsize                     <> DontCare
  crossbar.io.s00_axi_awburst                    <> DontCare
  crossbar.io.s00_axi_awlock                     <> DontCare
  crossbar.io.s00_axi_awcache                    <> DontCare
  crossbar.io.s00_axi_awprot                     <> DontCare
  crossbar.io.s00_axi_awqos                      <> DontCare
  crossbar.io.s00_axi_awuser                     <> DontCare
  crossbar.io.s00_axi_awvalid                    <> DontCare
  crossbar.io.s00_axi_awready                    <> DontCare
  crossbar.io.s00_axi_wdata                      <> DontCare
  crossbar.io.s00_axi_wstrb                      <> DontCare
  crossbar.io.s00_axi_wlast                      <> DontCare
  crossbar.io.s00_axi_wuser                      <> DontCare
  crossbar.io.s00_axi_wvalid                     <> DontCare
  crossbar.io.s00_axi_wready                     <> DontCare
  crossbar.io.s00_axi_bid                        <> DontCare
  crossbar.io.s00_axi_bresp                      <> DontCare
  crossbar.io.s00_axi_bvalid                     <> DontCare
  crossbar.io.s00_axi_bready                     <> DontCare

  // Memory related modules
  val dCache        = Module(new DCache)
  val uncachedAgent = Module(new UncachedAgent)
  val tlb           = Module(new Tlb)

  // Connection for memory related modules
  // TODO: Finish AXI connection
  dCache.io        <> DontCare
  uncachedAgent.io <> DontCare
  // Hint: dCache.io.axiMasterPort --> crossbar.io.slaves(1)
  crossbar.io.s01_axi_arid    <> dCache.io.axiMasterPort.arid
  crossbar.io.s01_axi_araddr  <> dCache.io.axiMasterPort.araddr
  crossbar.io.s01_axi_arlen   <> dCache.io.axiMasterPort.arlen
  crossbar.io.s01_axi_arsize  <> dCache.io.axiMasterPort.arsize
  crossbar.io.s01_axi_arburst <> dCache.io.axiMasterPort.arburst
  crossbar.io.s01_axi_arlock  <> dCache.io.axiMasterPort.arlock
  crossbar.io.s01_axi_arcache <> dCache.io.axiMasterPort.arcache
  crossbar.io.s01_axi_arprot  <> dCache.io.axiMasterPort.arprot
  crossbar.io.s01_axi_arqos   <> DontCare
  crossbar.io.s01_axi_aruser  <> DontCare
  crossbar.io.s01_axi_arvalid <> dCache.io.axiMasterPort.arvalid
  crossbar.io.s01_axi_arready <> dCache.io.axiMasterPort.arready
  crossbar.io.s01_axi_rid     <> dCache.io.axiMasterPort.rid
  crossbar.io.s01_axi_rdata   <> dCache.io.axiMasterPort.rdata
  crossbar.io.s01_axi_rresp   <> dCache.io.axiMasterPort.rresp
  crossbar.io.s01_axi_rlast   <> dCache.io.axiMasterPort.rlast
  crossbar.io.s01_axi_ruser   <> DontCare
  crossbar.io.s01_axi_rvalid  <> dCache.io.axiMasterPort.rvalid
  crossbar.io.s01_axi_rready  <> dCache.io.axiMasterPort.rready
  crossbar.io.s01_axi_awid    <> dCache.io.axiMasterPort.awid
  crossbar.io.s01_axi_awaddr  <> dCache.io.axiMasterPort.awaddr
  crossbar.io.s01_axi_awlen   <> dCache.io.axiMasterPort.awlen
  crossbar.io.s01_axi_awsize  <> dCache.io.axiMasterPort.awsize
  crossbar.io.s01_axi_awburst <> dCache.io.axiMasterPort.awburst
  crossbar.io.s01_axi_awlock  <> dCache.io.axiMasterPort.awlock
  crossbar.io.s01_axi_awcache <> dCache.io.axiMasterPort.awcache
  crossbar.io.s01_axi_awprot  <> dCache.io.axiMasterPort.awprot
  crossbar.io.s01_axi_awqos   <> DontCare
  crossbar.io.s01_axi_awuser  <> DontCare
  crossbar.io.s01_axi_awvalid <> dCache.io.axiMasterPort.awvalid
  crossbar.io.s01_axi_awready <> dCache.io.axiMasterPort.awready
  crossbar.io.s01_axi_wdata   <> dCache.io.axiMasterPort.wdata
  crossbar.io.s01_axi_wstrb   <> dCache.io.axiMasterPort.wstrb
  crossbar.io.s01_axi_wlast   <> dCache.io.axiMasterPort.wlast
  crossbar.io.s01_axi_wuser   <> DontCare
  crossbar.io.s01_axi_wvalid  <> dCache.io.axiMasterPort.wvalid
  crossbar.io.s01_axi_wready  <> dCache.io.axiMasterPort.wready
  crossbar.io.s01_axi_bid     <> dCache.io.axiMasterPort.bid
  crossbar.io.s01_axi_bresp   <> dCache.io.axiMasterPort.bresp
  crossbar.io.s01_axi_bvalid  <> dCache.io.axiMasterPort.bvalid
  crossbar.io.s01_axi_bready  <> dCache.io.axiMasterPort.bready
  // Hint: uncachedAgent.io.axiMasterPort --> crossbar.io.slaves(1)
  crossbar.io.s02_axi_awid    <> uncachedAgent.io.axiMasterPort.awid
  crossbar.io.s02_axi_awaddr  <> uncachedAgent.io.axiMasterPort.awaddr
  crossbar.io.s02_axi_awlen   <> uncachedAgent.io.axiMasterPort.awlen
  crossbar.io.s02_axi_awsize  <> uncachedAgent.io.axiMasterPort.awsize
  crossbar.io.s02_axi_awburst <> uncachedAgent.io.axiMasterPort.awburst
  crossbar.io.s02_axi_awlock  <> uncachedAgent.io.axiMasterPort.awlock
  crossbar.io.s02_axi_awcache <> uncachedAgent.io.axiMasterPort.awcache
  crossbar.io.s02_axi_awprot  <> uncachedAgent.io.axiMasterPort.awprot
  crossbar.io.s02_axi_awqos   <> DontCare
  crossbar.io.s02_axi_awuser  <> DontCare
  crossbar.io.s02_axi_awvalid <> uncachedAgent.io.axiMasterPort.awvalid
  crossbar.io.s02_axi_awready <> uncachedAgent.io.axiMasterPort.awready
  crossbar.io.s02_axi_wdata   <> uncachedAgent.io.axiMasterPort.wdata
  crossbar.io.s02_axi_wstrb   <> uncachedAgent.io.axiMasterPort.wstrb
  crossbar.io.s02_axi_wlast   <> uncachedAgent.io.axiMasterPort.wlast
  crossbar.io.s02_axi_wuser   <> DontCare
  crossbar.io.s02_axi_wvalid  <> uncachedAgent.io.axiMasterPort.wvalid
  crossbar.io.s02_axi_wready  <> uncachedAgent.io.axiMasterPort.wready
  crossbar.io.s02_axi_bid     <> uncachedAgent.io.axiMasterPort.bid
  crossbar.io.s02_axi_bresp   <> uncachedAgent.io.axiMasterPort.bresp
  crossbar.io.s02_axi_buser   <> DontCare
  crossbar.io.s02_axi_bvalid  <> uncachedAgent.io.axiMasterPort.bvalid
  crossbar.io.s02_axi_bready  <> uncachedAgent.io.axiMasterPort.bready
  crossbar.io.s02_axi_arid    <> uncachedAgent.io.axiMasterPort.arid
  crossbar.io.s02_axi_araddr  <> uncachedAgent.io.axiMasterPort.araddr
  crossbar.io.s02_axi_arlen   <> uncachedAgent.io.axiMasterPort.arlen
  crossbar.io.s02_axi_arsize  <> uncachedAgent.io.axiMasterPort.arsize
  crossbar.io.s02_axi_arburst <> uncachedAgent.io.axiMasterPort.arburst
  crossbar.io.s02_axi_arlock  <> uncachedAgent.io.axiMasterPort.arlock
  crossbar.io.s02_axi_arcache <> uncachedAgent.io.axiMasterPort.arcache
  crossbar.io.s02_axi_arprot  <> uncachedAgent.io.axiMasterPort.arprot
  crossbar.io.s02_axi_arqos   <> DontCare
  crossbar.io.s02_axi_aruser  <> DontCare
  crossbar.io.s02_axi_arvalid <> uncachedAgent.io.axiMasterPort.arvalid
  crossbar.io.s02_axi_arready <> uncachedAgent.io.axiMasterPort.arready
  crossbar.io.s02_axi_rid     <> uncachedAgent.io.axiMasterPort.rid
  crossbar.io.s02_axi_rdata   <> uncachedAgent.io.axiMasterPort.rdata
  crossbar.io.s02_axi_rresp   <> uncachedAgent.io.axiMasterPort.rresp
  crossbar.io.s02_axi_rlast   <> uncachedAgent.io.axiMasterPort.rlast
  crossbar.io.s02_axi_ruser   <> DontCare
  crossbar.io.s02_axi_rvalid  <> uncachedAgent.io.axiMasterPort.rvalid
  crossbar.io.s02_axi_rready  <> uncachedAgent.io.axiMasterPort.rready

  // Simple fetch stage
  instQueue.io.enqueuePort                <> simpleFetchStage.io.instEnqueuePort
  instQueue.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.instQueue)
  simpleFetchStage.io.pc                  := pc.io.pc
  simpleFetchStage.io.pipelineControlPort := cu.io.pipelineControlPorts(PipelineStageIndex.instQueue)
  pc.io.isNext                            := simpleFetchStage.io.isPcNext

  // Issue stage
  issueStage.io.fetchInstDecodePort.bits   := instQueue.io.dequeuePort.bits.decode
  issueStage.io.instInfoPassThroughPort.in := instQueue.io.dequeuePort.bits.instInfo
  issueStage.io.fetchInstDecodePort.valid  := instQueue.io.dequeuePort.valid
  instQueue.io.dequeuePort.ready           := issueStage.io.fetchInstDecodePort.ready
  issueStage.io.regScores                  := scoreboard.io.regScores
  scoreboard.io.occupyPorts                := issueStage.io.occupyPorts
  issueStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.issueStage)
  issueStage.io.csrRegScores               := csrScoreBoard.io.regScores
  csrScoreBoard.io.occupyPorts             := issueStage.io.csrOccupyPorts

  // scoreboard.io.freePorts(0)    := exeStage.io.freePorts
  // scoreboard.io.freePorts(1)    := memStage.io.freePorts
  // scoreboard.io.freePorts(2)    := wbStage.io.freePorts(0)
  scoreboard.io.freePorts(0)    := wbStage.io.freePorts(0)
  csrScoreBoard.io.freePorts(0) := wbStage.io.csrFreePorts(0)

  // Reg-read stage
  regReadStage.io.issuedInfoPort             := issueStage.io.issuedInfoPort
  regReadStage.io.gprReadPorts(0)            <> regFile.io.readPorts(0)
  regReadStage.io.gprReadPorts(1)            <> regFile.io.readPorts(1)
  regReadStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.regReadStage)
  regReadStage.io.instInfoPassThroughPort.in := issueStage.io.instInfoPassThroughPort.out
  // regReadStage.io.dataforwardPorts.zip(dataforward.io.readPorts).foreach {
  //   case (regRead, df) => regRead <> df
  // }

  // Execution stage
  exeStage.io.exeInstPort                := regReadStage.io.exeInstPort
  exeStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.exeStage)
  exeStage.io.instInfoPassThroughPort.in := regReadStage.io.instInfoPassThroughPort.out

  // Mem stages
  // TODO: Also finish the connection here
  addrTransStage.io                            <> DontCare
  memReqStage.io                               <> DontCare
  memResStage.io                               <> DontCare
  addrTransStage.io.gprWritePassThroughPort.in := exeStage.io.gprWritePort
  addrTransStage.io.instInfoPassThroughPort.in := exeStage.io.instInfoPassThroughPort.out
  addrTransStage.io.memAccessPort              := exeStage.io.memAccessPort
  addrTransStage.io.tlbTransPort               <> tlb.io.tlbTransPorts(0)
  // TODO: CSR
  memReqStage.io.translatedMemRequestPort   := addrTransStage.io.translatedMemRequestPort
  memReqStage.io.isCachedAccess.in          := addrTransStage.io.isCachedAccess
  memReqStage.io.gprWritePassThroughPort.in := addrTransStage.io.gprWritePassThroughPort.out
  memReqStage.io.instInfoPassThroughPort.in := addrTransStage.io.instInfoPassThroughPort.out
  dCache.io.accessPort.req.client           := memReqStage.io.dCacheRequestPort
  uncachedAgent.io.accessPort.req.client    := memReqStage.io.uncachedRequestPort
  dCache.io.accessPort.req.isReady          <> DontCare // Assume ready guaranteed by in-order access
  uncachedAgent.io.accessPort.req.isReady   <> DontCare // Assume ready guaranteed by in-order access
  memResStage.io.isHasRequest               := memReqStage.io.isHasRequest
  memResStage.io.isCachedRequest            := memReqStage.io.isCachedAccess.out
  memResStage.io.gprWritePassThroughPort.in := addrTransStage.io.gprWritePassThroughPort.out
  memResStage.io.instInfoPassThroughPort.in := addrTransStage.io.instInfoPassThroughPort.out
  memResStage.io.dCacheResponsePort         := dCache.io.accessPort.res
  memResStage.io.uncachedResponsePort       := uncachedAgent.io.accessPort.res

  // Write-back stage
  wbStage.io.gprWriteInfoPort           := memResStage.io.gprWritePassThroughPort.out
  wbStage.io.instInfoPassThroughPort.in := memResStage.io.instInfoPassThroughPort.out
  regFile.io.writePort                  := cu.io.gprWritePassThroughPorts.out(0)

  // data forward
  // dataforward.io.writePorts(0) := exeStage.io.gprWritePort
  // dataforward.io.writePorts(1) := memStage.io.gprWritePassThroughPort.out

  // Ctrl unit
  cu.io.instInfoPorts(0)               := wbStage.io.instInfoPassThroughPort.out
  cu.io.exeStallRequest                := exeStage.io.stallRequest
  cu.io.memStallRequest                := false.B // memStage.io.stallRequest    *********** TODO
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
  (io.diffTest, csr.io.difftest) match {
    case (Some(t), Some(c)) =>
      t.csr_crmd_diff_0      := c.crmd
      t.csr_prmd_diff_0      := c.prmd
      t.csr_ectl_diff_0      := c.ectl
      t.csr_estat_diff_0     := c.estat.asUInt
      t.csr_era_diff_0       := c.era
      t.csr_badv_diff_0      := c.badv
      t.csr_eentry_diff_0    := c.eentry
      t.csr_tlbidx_diff_0    := c.tlbidx
      t.csr_tlbehi_diff_0    := c.tlbehi
      t.csr_tlbelo0_diff_0   := c.tlbelo0
      t.csr_tlbelo1_diff_0   := c.tlbelo1
      t.csr_asid_diff_0      := c.asid
      t.csr_save0_diff_0     := c.save0
      t.csr_save1_diff_0     := c.save1
      t.csr_save2_diff_0     := c.save2
      t.csr_save3_diff_0     := c.save3
      t.csr_tid_diff_0       := c.tid
      t.csr_tcfg_diff_0      := c.tcfg
      t.csr_tval_diff_0      := c.tval
      t.csr_ticlr_diff_0     := c.ticlr
      t.csr_llbctl_diff_0    := c.llbctl
      t.csr_tlbrentry_diff_0 := c.tlbrentry
      t.csr_dmw0_diff_0      := c.dmw0
      t.csr_dmw1_diff_0      := c.dmw1
      t.csr_pgdl_diff_0      := c.pgdl
      t.csr_pgdh_diff_0      := c.pgdh

      t.cmt_csr_ecode := c.estat.ecode
    case _ =>
  }
}
