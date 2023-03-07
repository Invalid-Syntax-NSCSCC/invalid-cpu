import chisel3._
import chisel3.experimental.FlatIO
import chisel3.util._
import frontend.InstQueue

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

  // TODO: Remove temporary test content
  val testReg = RegNext(true.B, false.B)
  io.bready := testReg

  val instQueue = Module(new InstQueue)
}
