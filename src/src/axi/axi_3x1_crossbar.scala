package axi

import axi.bundles.{AxiMasterInterface, AxiSlaveInterface}
import chisel3._
import spec.Param

class Axi3x1Crossbar extends Module {
  val io = IO(new Bundle {
    val master = Vec(1, new AxiMasterInterface)
    val slave  = Vec(3, new AxiSlaveInterface)
  })

  val rawModule = Module(new axi_3x1_crossbar)
  rawModule.io.clk := clock
  rawModule.io.rst := reset

  rawModule.io.s00_axi_awid :<>= io.master(0).aw.bits.id
  rawModule.io.s00_axi_awaddr :<>= io.master(0).aw.bits.addr
  rawModule.io.s00_axi_awlen :<>= io.master(0).aw.bits.len
  rawModule.io.s00_axi_awsize :<>= io.master(0).aw.bits.size
  rawModule.io.s00_axi_awburst :<>= io.master(0).aw.bits.burst
  rawModule.io.s00_axi_awlock :<>= io.master(0).aw.bits.lock
  rawModule.io.s00_axi_awcache :<>= io.master(0).aw.bits.cache
  rawModule.io.s00_axi_awprot :<>= io.master(0).aw.bits.prot
  rawModule.io.s00_axi_awqos :<>= io.master(0).aw.bits.qos
  rawModule.io.s00_axi_awuser :<>= io.master(0).aw.bits.user
  rawModule.io.s00_axi_awvalid :<>= io.master(0).aw.valid
  rawModule.io.s00_axi_awready :<>= io.master(0).aw.ready
  rawModule.io.s00_axi_wdata :<>= io.master(0).w.bits.data
  rawModule.io.s00_axi_wstrb :<>= io.master(0).w.bits.strb
  rawModule.io.s00_axi_wlast :<>= io.master(0).w.bits.last
  rawModule.io.s00_axi_wuser :<>= io.master(0).w.bits.user
  rawModule.io.s00_axi_wvalid :<>= io.master(0).w.valid
  rawModule.io.s00_axi_wready :<>= io.master(0).w.ready
  rawModule.io.s00_axi_bid
  rawModule.io.s00_axi_bresp
  rawModule.io.s00_axi_buser
  rawModule.io.s00_axi_bvalid
  rawModule.io.s00_axi_bready
  rawModule.io.s00_axi_arid
  rawModule.io.s00_axi_araddr
  rawModule.io.s00_axi_arlen
  rawModule.io.s00_axi_arsize
  rawModule.io.s00_axi_arburst
  rawModule.io.s00_axi_arlock
  rawModule.io.s00_axi_arcache
  rawModule.io.s00_axi_arprot
  rawModule.io.s00_axi_arqos
  rawModule.io.s00_axi_aruser
  rawModule.io.s00_axi_arvalid
  rawModule.io.s00_axi_arready
  rawModule.io.s00_axi_rid
  rawModule.io.s00_axi_rdata
  rawModule.io.s00_axi_rresp
  rawModule.io.s00_axi_rlast
  rawModule.io.s00_axi_ruser
  rawModule.io.s00_axi_rvalid
  rawModule.io.s00_axi_rready
  rawModule.io.s01_axi_awid
  rawModule.io.s01_axi_awaddr
  rawModule.io.s01_axi_awlen
  rawModule.io.s01_axi_awsize
  rawModule.io.s01_axi_awburst
  rawModule.io.s01_axi_awlock
  rawModule.io.s01_axi_awcache
  rawModule.io.s01_axi_awprot
  rawModule.io.s01_axi_awqos
  rawModule.io.s01_axi_awuser
  rawModule.io.s01_axi_awvalid
  rawModule.io.s01_axi_awready
  rawModule.io.s01_axi_wdata
  rawModule.io.s01_axi_wstrb
  rawModule.io.s01_axi_wlast
  rawModule.io.s01_axi_wuser
  rawModule.io.s01_axi_wvalid
  rawModule.io.s01_axi_wready
  rawModule.io.s01_axi_bid
  rawModule.io.s01_axi_bresp
  rawModule.io.s01_axi_buser
  rawModule.io.s01_axi_bvalid
  rawModule.io.s01_axi_bready
  rawModule.io.s01_axi_arid
  rawModule.io.s01_axi_araddr
  rawModule.io.s01_axi_arlen
  rawModule.io.s01_axi_arsize
  rawModule.io.s01_axi_arburst
  rawModule.io.s01_axi_arlock
  rawModule.io.s01_axi_arcache
  rawModule.io.s01_axi_arprot
  rawModule.io.s01_axi_arqos
  rawModule.io.s01_axi_aruser
  rawModule.io.s01_axi_arvalid
  rawModule.io.s01_axi_arready
  rawModule.io.s01_axi_rid
  rawModule.io.s01_axi_rdata
  rawModule.io.s01_axi_rresp
  rawModule.io.s01_axi_rlast
  rawModule.io.s01_axi_ruser
  rawModule.io.s01_axi_rvalid
  rawModule.io.s01_axi_rready
  rawModule.io.s02_axi_awid
  rawModule.io.s02_axi_awaddr
  rawModule.io.s02_axi_awlen
  rawModule.io.s02_axi_awsize
  rawModule.io.s02_axi_awburst
  rawModule.io.s02_axi_awlock
  rawModule.io.s02_axi_awcache
  rawModule.io.s02_axi_awprot
  rawModule.io.s02_axi_awqos
  rawModule.io.s02_axi_awuser
  rawModule.io.s02_axi_awvalid
  rawModule.io.s02_axi_awready
  rawModule.io.s02_axi_wdata
  rawModule.io.s02_axi_wstrb
  rawModule.io.s02_axi_wlast
  rawModule.io.s02_axi_wuser
  rawModule.io.s02_axi_wvalid
  rawModule.io.s02_axi_wready
  rawModule.io.s02_axi_bid
  rawModule.io.s02_axi_bresp
  rawModule.io.s02_axi_buser
  rawModule.io.s02_axi_bvalid
  rawModule.io.s02_axi_bready
  rawModule.io.s02_axi_arid
  rawModule.io.s02_axi_araddr
  rawModule.io.s02_axi_arlen
  rawModule.io.s02_axi_arsize
  rawModule.io.s02_axi_arburst
  rawModule.io.s02_axi_arlock
  rawModule.io.s02_axi_arcache
  rawModule.io.s02_axi_arprot
  rawModule.io.s02_axi_arqos
  rawModule.io.s02_axi_aruser
  rawModule.io.s02_axi_arvalid
  rawModule.io.s02_axi_arready
  rawModule.io.s02_axi_rid
  rawModule.io.s02_axi_rdata
  rawModule.io.s02_axi_rresp
  rawModule.io.s02_axi_rlast
  rawModule.io.s02_axi_ruser
  rawModule.io.s02_axi_rvalid
  rawModule.io.s02_axi_rready
  rawModule.io.m00_axi_awid
  rawModule.io.m00_axi_awaddr
  rawModule.io.m00_axi_awlen
  rawModule.io.m00_axi_awsize
  rawModule.io.m00_axi_awburst
  rawModule.io.m00_axi_awlock
  rawModule.io.m00_axi_awcache
  rawModule.io.m00_axi_awprot
  rawModule.io.m00_axi_awqos
  rawModule.io.m00_axi_awregion
  rawModule.io.m00_axi_awuser
  rawModule.io.m00_axi_awvalid
  rawModule.io.m00_axi_awready
  rawModule.io.m00_axi_wdata
  rawModule.io.m00_axi_wstrb
  rawModule.io.m00_axi_wlast
  rawModule.io.m00_axi_wuser
  rawModule.io.m00_axi_wvalid
  rawModule.io.m00_axi_wready
  rawModule.io.m00_axi_bid
  rawModule.io.m00_axi_bresp
  rawModule.io.m00_axi_buser
  rawModule.io.m00_axi_bvalid
  rawModule.io.m00_axi_bready
  rawModule.io.m00_axi_arid
  rawModule.io.m00_axi_araddr
  rawModule.io.m00_axi_arlen
  rawModule.io.m00_axi_arsize
  rawModule.io.m00_axi_arburst
  rawModule.io.m00_axi_arlock
  rawModule.io.m00_axi_arcache
  rawModule.io.m00_axi_arprot
  rawModule.io.m00_axi_arqos
  rawModule.io.m00_axi_arregion
  rawModule.io.m00_axi_aruser
  rawModule.io.m00_axi_arvalid
  rawModule.io.m00_axi_arready
  rawModule.io.m00_axi_rid
  rawModule.io.m00_axi_rdata
  rawModule.io.m00_axi_rresp
  rawModule.io.m00_axi_rlast
  rawModule.io.m00_axi_ruser
  rawModule.io.m00_axi_rvalid
  rawModule.io.m00_axi_rready
}

class axi_3x1_crossbar
    extends BlackBox(
      Map(
        "ADDR_WIDTH" -> spec.Width.Axi.addr.get,
        "DATA_WIDTH" -> spec.Width.Axi.data.get
      )
    ) {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())

    val s00_axi_awid    = Input(UInt(Param.Width.Axi.slaveId.W))
    val s00_axi_awaddr  = Input(UInt(spec.Width.Axi.addr))
    val s00_axi_awlen   = Input(UInt(8.W))
    val s00_axi_awsize  = Input(UInt(3.W))
    val s00_axi_awburst = Input(UInt(2.W))
    val s00_axi_awlock  = Input(Bool())
    val s00_axi_awcache = Input(UInt(4.W))
    val s00_axi_awprot  = Input(UInt(3.W))
    val s00_axi_awqos   = Input(UInt(4.W))
    val s00_axi_awuser  = Input(UInt(spec.Width.Axi.awuser))
    val s00_axi_awvalid = Input(Bool())
    val s00_axi_awready = Output(Bool())
    val s00_axi_wdata   = Input(UInt(spec.Width.Axi.data))
    val s00_axi_wstrb   = Input(UInt(spec.Width.Axi.strb))
    val s00_axi_wlast   = Input(Bool())
    val s00_axi_wuser   = Input(UInt(spec.Width.Axi.wuser))
    val s00_axi_wvalid  = Input(Bool())
    val s00_axi_wready  = Output(Bool())
    val s00_axi_bid     = Output(UInt(Param.Width.Axi.slaveId.W))
    val s00_axi_bresp   = Output(UInt(2.W))
    val s00_axi_buser   = Output(UInt(spec.Width.Axi.buser))
    val s00_axi_bvalid  = Output(Bool())
    val s00_axi_bready  = Input(Bool())
    val s00_axi_arid    = Input(UInt(Param.Width.Axi.slaveId.W))
    val s00_axi_araddr  = Input(UInt(spec.Width.Axi.addr))
    val s00_axi_arlen   = Input(UInt(8.W))
    val s00_axi_arsize  = Input(UInt(3.W))
    val s00_axi_arburst = Input(UInt(2.W))
    val s00_axi_arlock  = Input(Bool())
    val s00_axi_arcache = Input(UInt(4.W))
    val s00_axi_arprot  = Input(UInt(3.W))
    val s00_axi_arqos   = Input(UInt(3.W))
    val s00_axi_aruser  = Input(UInt(spec.Width.Axi.aruser))
    val s00_axi_arvalid = Input(Bool())
    val s00_axi_arready = Output(Bool())
    val s00_axi_rid     = Output(UInt(Param.Width.Axi.slaveId.W))
    val s00_axi_rdata   = Output(UInt(spec.Width.Axi.data))
    val s00_axi_rresp   = Output(UInt(2.W))
    val s00_axi_rlast   = Output(Bool())
    val s00_axi_ruser   = Output(UInt(spec.Width.Axi.ruser))
    val s00_axi_rvalid  = Output(Bool())
    val s00_axi_rready  = Input(Bool())

    val s01_axi_awid    = Input(UInt(Param.Width.Axi.slaveId.W))
    val s01_axi_awaddr  = Input(UInt(spec.Width.Axi.addr))
    val s01_axi_awlen   = Input(UInt(8.W))
    val s01_axi_awsize  = Input(UInt(3.W))
    val s01_axi_awburst = Input(UInt(2.W))
    val s01_axi_awlock  = Input(Bool())
    val s01_axi_awcache = Input(UInt(4.W))
    val s01_axi_awprot  = Input(UInt(3.W))
    val s01_axi_awqos   = Input(UInt(4.W))
    val s01_axi_awuser  = Input(UInt(spec.Width.Axi.awuser))
    val s01_axi_awvalid = Input(Bool())
    val s01_axi_awready = Output(Bool())
    val s01_axi_wdata   = Input(UInt(spec.Width.Axi.data))
    val s01_axi_wstrb   = Input(UInt(spec.Width.Axi.strb))
    val s01_axi_wlast   = Input(Bool())
    val s01_axi_wuser   = Input(UInt(spec.Width.Axi.wuser))
    val s01_axi_wvalid  = Input(Bool())
    val s01_axi_wready  = Output(Bool())
    val s01_axi_bid     = Output(UInt(Param.Width.Axi.slaveId.W))
    val s01_axi_bresp   = Output(UInt(2.W))
    val s01_axi_buser   = Output(UInt(spec.Width.Axi.buser))
    val s01_axi_bvalid  = Output(Bool())
    val s01_axi_bready  = Input(Bool())
    val s01_axi_arid    = Input(UInt(Param.Width.Axi.slaveId.W))
    val s01_axi_araddr  = Input(UInt(spec.Width.Axi.addr))
    val s01_axi_arlen   = Input(UInt(8.W))
    val s01_axi_arsize  = Input(UInt(3.W))
    val s01_axi_arburst = Input(UInt(2.W))
    val s01_axi_arlock  = Input(Bool())
    val s01_axi_arcache = Input(UInt(4.W))
    val s01_axi_arprot  = Input(UInt(3.W))
    val s01_axi_arqos   = Input(UInt(3.W))
    val s01_axi_aruser  = Input(UInt(spec.Width.Axi.aruser))
    val s01_axi_arvalid = Input(Bool())
    val s01_axi_arready = Output(Bool())
    val s01_axi_rid     = Output(UInt(Param.Width.Axi.slaveId.W))
    val s01_axi_rdata   = Output(UInt(spec.Width.Axi.data))
    val s01_axi_rresp   = Output(UInt(2.W))
    val s01_axi_rlast   = Output(Bool())
    val s01_axi_ruser   = Output(UInt(spec.Width.Axi.ruser))
    val s01_axi_rvalid  = Output(Bool())
    val s01_axi_rready  = Input(Bool())

    val s02_axi_awid    = Input(UInt(Param.Width.Axi.slaveId.W))
    val s02_axi_awaddr  = Input(UInt(spec.Width.Axi.addr))
    val s02_axi_awlen   = Input(UInt(8.W))
    val s02_axi_awsize  = Input(UInt(3.W))
    val s02_axi_awburst = Input(UInt(2.W))
    val s02_axi_awlock  = Input(Bool())
    val s02_axi_awcache = Input(UInt(4.W))
    val s02_axi_awprot  = Input(UInt(3.W))
    val s02_axi_awqos   = Input(UInt(4.W))
    val s02_axi_awuser  = Input(UInt(spec.Width.Axi.awuser))
    val s02_axi_awvalid = Input(Bool())
    val s02_axi_awready = Output(Bool())
    val s02_axi_wdata   = Input(UInt(spec.Width.Axi.data))
    val s02_axi_wstrb   = Input(UInt(spec.Width.Axi.strb))
    val s02_axi_wlast   = Input(Bool())
    val s02_axi_wuser   = Input(UInt(spec.Width.Axi.wuser))
    val s02_axi_wvalid  = Input(Bool())
    val s02_axi_wready  = Output(Bool())
    val s02_axi_bid     = Output(UInt(Param.Width.Axi.slaveId.W))
    val s02_axi_bresp   = Output(UInt(2.W))
    val s02_axi_buser   = Output(UInt(spec.Width.Axi.buser))
    val s02_axi_bvalid  = Output(Bool())
    val s02_axi_bready  = Input(Bool())
    val s02_axi_arid    = Input(UInt(Param.Width.Axi.slaveId.W))
    val s02_axi_araddr  = Input(UInt(spec.Width.Axi.addr))
    val s02_axi_arlen   = Input(UInt(8.W))
    val s02_axi_arsize  = Input(UInt(3.W))
    val s02_axi_arburst = Input(UInt(2.W))
    val s02_axi_arlock  = Input(Bool())
    val s02_axi_arcache = Input(UInt(4.W))
    val s02_axi_arprot  = Input(UInt(3.W))
    val s02_axi_arqos   = Input(UInt(3.W))
    val s02_axi_aruser  = Input(UInt(spec.Width.Axi.aruser))
    val s02_axi_arvalid = Input(Bool())
    val s02_axi_arready = Output(Bool())
    val s02_axi_rid     = Output(UInt(Param.Width.Axi.slaveId.W))
    val s02_axi_rdata   = Output(UInt(spec.Width.Axi.data))
    val s02_axi_rresp   = Output(UInt(2.W))
    val s02_axi_rlast   = Output(Bool())
    val s02_axi_ruser   = Output(UInt(spec.Width.Axi.ruser))
    val s02_axi_rvalid  = Output(Bool())
    val s02_axi_rready  = Input(Bool())

    val m00_axi_awid     = Output(UInt(Param.Width.Axi.masterId.W))
    val m00_axi_awaddr   = Output(UInt(spec.Width.Axi.addr))
    val m00_axi_awlen    = Output(UInt(8.W))
    val m00_axi_awsize   = Output(UInt(3.W))
    val m00_axi_awburst  = Output(UInt(2.W))
    val m00_axi_awlock   = Output(Bool())
    val m00_axi_awcache  = Output(UInt(4.W))
    val m00_axi_awprot   = Output(UInt(3.W))
    val m00_axi_awqos    = Output(UInt(4.W))
    val m00_axi_awregion = Output(UInt(4.W))
    val m00_axi_awuser   = Output(UInt(spec.Width.Axi.awuser))
    val m00_axi_awvalid  = Output(Bool())
    val m00_axi_awready  = Input(Bool())
    val m00_axi_wdata    = Output(UInt(spec.Width.Axi.data))
    val m00_axi_wstrb    = Output(UInt(spec.Width.Axi.strb))
    val m00_axi_wlast    = Output(Bool())
    val m00_axi_wuser    = Output(UInt(spec.Width.Axi.wuser))
    val m00_axi_wvalid   = Output(Bool())
    val m00_axi_wready   = Input(Bool())
    val m00_axi_bid      = Input(UInt(Param.Width.Axi.masterId.W))
    val m00_axi_bresp    = Input(UInt(2.W))
    val m00_axi_buser    = Input(UInt(spec.Width.Axi.buser))
    val m00_axi_bvalid   = Input(Bool())
    val m00_axi_bready   = Output(Bool())
    val m00_axi_arid     = Output(UInt(Param.Width.Axi.masterId.W))
    val m00_axi_araddr   = Output(UInt(spec.Width.Axi.addr))
    val m00_axi_arlen    = Output(UInt(8.W))
    val m00_axi_arsize   = Output(UInt(3.W))
    val m00_axi_arburst  = Output(UInt(2.W))
    val m00_axi_arlock   = Output(Bool())
    val m00_axi_arcache  = Output(UInt(4.W))
    val m00_axi_arprot   = Output(UInt(3.W))
    val m00_axi_arqos    = Output(UInt(3.W))
    val m00_axi_arregion = Output(UInt(4.W))
    val m00_axi_aruser   = Output(UInt(spec.Width.Axi.aruser))
    val m00_axi_arvalid  = Output(Bool())
    val m00_axi_arready  = Input(Bool())
    val m00_axi_rid      = Input(UInt(Param.Width.Axi.masterId.W))
    val m00_axi_rdata    = Input(UInt(spec.Width.Axi.data))
    val m00_axi_rresp    = Input(UInt(2.W))
    val m00_axi_rlast    = Input(Bool())
    val m00_axi_ruser    = Input(UInt(spec.Width.Axi.ruser))
    val m00_axi_rvalid   = Input(Bool())
    val m00_axi_rready   = Output(Bool())
  })

}
