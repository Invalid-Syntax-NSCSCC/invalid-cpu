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
  io.arid                                        := simpleFetchStage.io.axiMasterInterface.arid
  io.araddr                                      := simpleFetchStage.io.axiMasterInterface.araddr
  io.arlen                                       := simpleFetchStage.io.axiMasterInterface.arlen
  io.arsize                                      := simpleFetchStage.io.axiMasterInterface.arsize
  io.arburst                                     := simpleFetchStage.io.axiMasterInterface.arburst
  io.arlock                                      := simpleFetchStage.io.axiMasterInterface.arlock
  io.arcache                                     := simpleFetchStage.io.axiMasterInterface.arcache
  io.arprot                                      := simpleFetchStage.io.axiMasterInterface.arprot
  io.arvalid                                     := simpleFetchStage.io.axiMasterInterface.arvalid
  simpleFetchStage.io.axiMasterInterface.arready := io.arready

  simpleFetchStage.io.axiMasterInterface.rid    := io.rid
  simpleFetchStage.io.axiMasterInterface.rdata  := io.rdata
  simpleFetchStage.io.axiMasterInterface.rresp  := io.rresp
  simpleFetchStage.io.axiMasterInterface.rlast  := io.rlast
  simpleFetchStage.io.axiMasterInterface.rvalid := io.rvalid
  io.rready                                     := simpleFetchStage.io.axiMasterInterface.rready

  io.awid                                        := simpleFetchStage.io.axiMasterInterface.awid
  io.awaddr                                      := simpleFetchStage.io.axiMasterInterface.awaddr
  io.awlen                                       := simpleFetchStage.io.axiMasterInterface.awlen
  io.awsize                                      := simpleFetchStage.io.axiMasterInterface.awsize
  io.awburst                                     := simpleFetchStage.io.axiMasterInterface.awburst
  io.awlock                                      := simpleFetchStage.io.axiMasterInterface.awlock
  io.awcache                                     := simpleFetchStage.io.axiMasterInterface.awcache
  io.awprot                                      := simpleFetchStage.io.axiMasterInterface.awprot
  io.awvalid                                     := simpleFetchStage.io.axiMasterInterface.awvalid
  simpleFetchStage.io.axiMasterInterface.awready := io.awready

  io.wid                                        := simpleFetchStage.io.axiMasterInterface.wid
  io.wdata                                      := simpleFetchStage.io.axiMasterInterface.wdata
  io.wstrb                                      := simpleFetchStage.io.axiMasterInterface.wstrb
  io.wlast                                      := simpleFetchStage.io.axiMasterInterface.wlast
  io.wvalid                                     := simpleFetchStage.io.axiMasterInterface.wvalid
  simpleFetchStage.io.axiMasterInterface.wready := io.wready

  simpleFetchStage.io.axiMasterInterface.bid    := io.bid
  simpleFetchStage.io.axiMasterInterface.bresp  := io.bresp
  simpleFetchStage.io.axiMasterInterface.bvalid := io.bvalid
  io.bready                                     := simpleFetchStage.io.axiMasterInterface.bready

  // Debug ports
  io.debug0_wb.pc       := pc.io.pc
  io.debug0_wb.rf.wen   := regFile.io.writePort.en
  io.debug0_wb.rf.wnum  := regFile.io.writePort.addr
  io.debug0_wb.rf.wdata := regFile.io.writePort.data
  io.debug0_wb.inst     := 0.U // TODO: Make connections correct

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
