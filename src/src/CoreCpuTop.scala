import axi.AxiCrossbar
import axi.bundles.AxiMasterPort
import chisel3._
import common.{Pc, RegFile}
import frontend.{InstQueue, SimpleFetchStage}
import pipeline.ctrl.Cu
import pipeline.dispatch.{IssueStage, RegReadStage, Scoreboard}
import pipeline.execution.ExeStage
import pipeline.writeback.WbStage
import pipeline.mem.MemStage
import spec.PipelineStageIndex

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

  val crossbar = Module(new AxiCrossbar)

  val scoreboard = Module(new Scoreboard)

  val regFile = Module(new RegFile)
  val pc      = Module(new Pc)

  // Default DontCare
  instQueue.io    <> DontCare
  issueStage.io   <> DontCare
  regReadStage.io <> DontCare
  regFile.io      <> DontCare
  scoreboard.io   <> DontCare
  cu.io           <> DontCare

  // TODO: Other connections
  exeStage.io := DontCare

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
  instQueue.io.enqueuePort <> simpleFetchStage.io.instEnqueuePort
  simpleFetchStage.io.pc   := pc.io.pc
  pc.io.isNext             := simpleFetchStage.io.isPcNext

  // Issue stage
  issueStage.io.fetchInstInfoPort   <> instQueue.io.dequeuePort
  issueStage.io.regScores           := scoreboard.io.regScores
  scoreboard.io.occupyPorts         := issueStage.io.occupyPorts
  issueStage.io.pipelineControlPort := cu.io.pipelineControlPorts(PipelineStageIndex.issueStage)

  // Reg-read stage
  regReadStage.io.issuedInfoPort      := issueStage.io.issuedInfoPort
  regReadStage.io.gprReadPorts(0)     <> regFile.io.readPorts(0)
  regReadStage.io.gprReadPorts(1)     <> regFile.io.readPorts(1)
  regReadStage.io.pipelineControlPort := cu.io.pipelineControlPorts(PipelineStageIndex.regReadStage)
  regReadStage.io.wbDebugInst         := issueStage.io.wbDebugInst

  // Execution stage
  exeStage.io.exeInstPort               := regReadStage.io.exeInstPort
  exeStage.io.pipelineControlPort       := cu.io.pipelineControlPorts(PipelineStageIndex.exeStage)
  exeStage.io.wbDebugPassthroughPort.in := regReadStage.io.wbDebugPort

  // Mem stage
  memStage.io.gprWritePassThroughPort.in := exeStage.io.gprWritePort
  memStage.io.memLoadStoreInfoPort       := exeStage.io.memLoadStoreInfoPort
  memStage.io.pipelineControlPort        := cu.io.pipelineControlPorts(PipelineStageIndex.memStage)
  memStage.io.memLoadStorePort           <> DontCare
  memStage.io.wbDebugPassthroughPort.in  := exeStage.io.wbDebugPassthroughPort.out

  // Write-back stage
  wbStage.io.gprWriteInfoPort          := memStage.io.gprWritePassThroughPort.out
  wbStage.io.wbDebugPassthroughPort.in := memStage.io.wbDebugPassthroughPort.out
  regFile.io.writePort                 := wbStage.io.gprWritePort
  scoreboard.io.freePorts              := wbStage.io.freePorts

  // Debug ports
  io.debug0_wb.pc       := wbStage.io.wbDebugPassthroughPort.out.pc
  io.debug0_wb.rf.wen   := wbStage.io.gprWritePort.en
  io.debug0_wb.rf.wnum  := wbStage.io.gprWritePort.addr
  io.debug0_wb.rf.wdata := wbStage.io.gprWritePort.data
  io.debug0_wb.inst     := wbStage.io.wbDebugPassthroughPort.out.inst

  // Ctrl unit
  cu.io.exeStallRequest := exeStage.io.stallRequest
  cu.io.memStallRequest := memStage.io.stallRequest
}
