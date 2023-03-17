import chisel3._
import chisel3.experimental.FlatIO
import chisel3.util._
import common.{Pc, RegFile}
import frontend.{InstQueue, SimpleFetchStage}
import pipeline.dispatch.{IssueStage, RegReadStage, Scoreboard}
import pipeline.execution.ExeStage
import pipeline.writeback.WbStage

class CpuTop extends Module {
  val io = FlatIO(new Bundle {
    val intrpt = Input(UInt(8.W))

    // AXI interface is as follow

    // Read request
    val arid    = Output(UInt(4.W))
    val araddr  = Output(UInt(32.W))
    val arlen   = Output(UInt(8.W))
    val arsize  = Output(UInt(3.W))
    val arburst = Output(UInt(2.W))
    val arlock  = Output(UInt(2.W))
    val arcache = Output(UInt(4.W))
    val arprot  = Output(UInt(3.W))
    val arvalid = Output(Bool())
    val arready = Input(Bool())

    // Read back
    val rid    = Input(UInt(4.W))
    val rdata  = Input(UInt(32.W))
    val rresp  = Input(UInt(2.W))
    val rlast  = Input(Bool())
    val rvalid = Input(Bool())
    val rready = Output(Bool())

    // Write request
    val awid    = Output(UInt(4.W))
    val awaddr  = Output(UInt(32.W))
    val awlen   = Output(UInt(8.W))
    val awsize  = Output(UInt(3.W))
    val awburst = Output(UInt(2.W))
    val awlock  = Output(UInt(2.W))
    val awcache = Output(UInt(4.W))
    val awprot  = Output(UInt(3.W))
    val awvalid = Output(Bool())
    val awready = Input(Bool())

    // Write data
    val wid    = Output(UInt(4.W))
    val wdata  = Output(UInt(32.W))
    val wstrb  = Output(UInt(4.W))
    val wlast  = Output(Bool())
    val wvalid = Output(Bool())
    val wready = Input(Bool())

    // Write back
    val bid    = Input(UInt(4.W))
    val bresp  = Input(UInt(2.W))
    val bvalid = Input(Bool())
    val bready = Output(Bool())

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

  val scoreboard = Module(new Scoreboard)

  val regFile = Module(new RegFile)
  val pc      = Module(new Pc)

  // Default DontCare
  instQueue.io <> DontCare
  issueStage.io <> DontCare
  regReadStage.io <> DontCare
  regFile.io <> DontCare
  scoreboard.io <> DontCare

  // `SimpleFetchStage` <> AXI top
  simpleFetchStage.io.axiMasterInterface

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
