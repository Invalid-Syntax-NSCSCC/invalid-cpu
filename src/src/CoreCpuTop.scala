import axi.bundles.AxiMasterPort
import chisel3._
import chisel3.experimental.FlatIO
import common.{Pc, RegFile}
import frontend.{InstQueue, SimpleFetchStage}
import pipeline.dispatch.{IssueStage, RegReadStage, Scoreboard}
import pipeline.execution.ExeStage
import pipeline.writeback.WbStage
import pipeline.ctrl.CtrlStage

class CoreCpuTop extends Module {
  val io = FlatIO(new Bundle {
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
  val wbStage          = Module(new WbStage)
  val ctrlStage        = Module(new CtrlStage)

  val scoreboard = Module(new Scoreboard)

  val regFile = Module(new RegFile)
  val pc      = Module(new Pc)

  // Default DontCare
  instQueue.io    <> DontCare
  issueStage.io   <> DontCare
  regReadStage.io <> DontCare
  regFile.io      <> DontCare
  scoreboard.io   <> DontCare
  ctrlStage.io    <> DontCare

  // `SimpleFetchStage` <> AXI top
  io.axi <> simpleFetchStage.io.axiMasterInterface

  // Debug ports
  io.debug0_wb.pc       := pc.io.pc
  io.debug0_wb.rf.wen   := regFile.io.writePort.en
  io.debug0_wb.rf.wnum  := regFile.io.writePort.addr
  io.debug0_wb.rf.wdata := regFile.io.writePort.data
  io.debug0_wb.inst     := 0.U // TODO: Make connections correct

  // Simple fetch stage
  instQueue.io.enqueuePort <> simpleFetchStage.io.instEnqueuePort
  simpleFetchStage.io.pc   := pc.io.pc
  pc.io.isNext             := simpleFetchStage.io.isPcNext

  // Issue stage
  issueStage.io.fetchInstInfoPort   <> instQueue.io.dequeuePort
  issueStage.io.regScores           := scoreboard.io.regScores
  scoreboard.io.occupyPorts         := issueStage.io.occupyPorts
  issueStage.io.pipelineControlPort := ctrlStage.io.pipelineControlPorts(0)

  // Reg-read stage
  regReadStage.io.issuedInfoPort      := issueStage.io.issuedInfoPort
  regReadStage.io.gprReadPorts(0)     <> regFile.io.readPorts(0)
  regReadStage.io.gprReadPorts(1)     <> regFile.io.readPorts(1)
  regReadStage.io.pipelineControlPort := ctrlStage.io.pipelineControlPorts(1)

  // Execution stage
  exeStage.io.exeInstPort         := regReadStage.io.exeInstPort
  exeStage.io.pipelineControlPort := ctrlStage.io.pipelineControlPorts(2)

  // Write-back stage
  wbStage.io.gprWriteInfoPort := exeStage.io.gprWritePort
  regFile.io.writePort        := wbStage.io.gprWritePort
  scoreboard.io.freePorts     := wbStage.io.freePorts

  // ctrl
  ctrlStage.io.exeStallRequest := exeStage.io.stallRequest
}
