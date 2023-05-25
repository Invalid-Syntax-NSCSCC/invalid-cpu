package common.bundles

import chisel3._
import spec._

class AxiMasterPort extends Bundle {
  // Read request
  val arid    = Output(UInt(4.W))
  val araddr  = Output(UInt(Width.Axi.addr))
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
  val rdata  = Input(UInt(Width.Axi.data))
  val rresp  = Input(UInt(2.W))
  val rlast  = Input(Bool())
  val rvalid = Input(Bool())
  val rready = Output(Bool())

  // Write request
  val awid    = Output(UInt(4.W))
  val awaddr  = Output(UInt(Width.Axi.addr))
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
  val wdata  = Output(UInt(Width.Axi.data))
  val wstrb  = Output(UInt(Width.Axi.strb))
  val wlast  = Output(Bool())
  val wvalid = Output(Bool())
  val wready = Input(Bool())

  // Write back
  val bid    = Input(UInt(4.W))
  val bresp  = Input(UInt(2.W))
  val bvalid = Input(Bool())
  val bready = Output(Bool())
}
