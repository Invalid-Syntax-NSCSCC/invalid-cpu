import chisel3._
import chisel3.experimental.FlatIO
import chisel3.util._
import common.RegFile
import common.bundles.AxiMasterPort
import frontend.InstQueue
import pipeline.dispatch.{IssueStage, RegReadStage, Scoreboard}
import pipeline.execution.ExeStage
import pipeline.writeback.WbStage

class CpuTop extends Module {
  val io = FlatIO(new Bundle {
    val intrpt = Input(UInt(8.W))

    // AXI interface is as follow
    val axiMaster = new AxiMasterPort

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

  // TODO: Remove temporary test content
  val testReg = RegNext(true.B, false.B)
  io.axiMaster.bready := testReg

  val instQueue    = Module(new InstQueue)
  val issueStage   = Module(new IssueStage)
  val regReadStage = Module(new RegReadStage)
  val exeStage     = Module(new ExeStage)
  val wbStage      = Module(new WbStage)

  val scoreboard = Module(new Scoreboard)

  val regFile = Module(new RegFile)

  // Default DontCare
  instQueue.io <> DontCare
  issueStage.io <> DontCare
  regReadStage.io <> DontCare
  regFile.io <> DontCare
  scoreboard.io <> DontCare

  issueStage.io.fetchInstInfoPort <> instQueue.io.dequeuePort
  issueStage.io.regScores   := scoreboard.io.regScores
  scoreboard.io.occupyPorts := issueStage.io.occupyPorts

  regReadStage.io.issuedInfoPort := issueStage.io.issuedInfoPort
  regReadStage.io.gprReadPorts(0) <> regFile.io.readPorts(0)
  regReadStage.io.gprReadPorts(1) <> regFile.io.readPorts(1)

  exeStage.io.exeInstPort := regReadStage.io.exeInstPort

  wbStage.io.gprWriteInfoPort := exeStage.io.gprWritePort
  regFile.io.writePort        := wbStage.io.gprWritePort
  scoreboard.io.freePorts     := wbStage.io.freePorts
}
