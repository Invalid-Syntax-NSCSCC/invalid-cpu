package axi

import axi.bundles.AxiMasterInterface
import chisel3._
import spec.Param

class Axi3x1Crossbar extends Module {
  val io = IO(new Bundle {
    val master = Vec(1, new AxiMasterInterface)
    val slave  = Vec(3, Flipped(new AxiMasterInterface))
  })

  val blackbox = Module(new axi_3x1_crossbar)
  blackbox.io.clk := clock
  blackbox.io.rst := reset

  blackbox.io.s00_axi_awid    <> io.slave(0).aw.bits.id
  blackbox.io.s00_axi_awaddr  <> io.slave(0).aw.bits.addr
  blackbox.io.s00_axi_awlen   <> io.slave(0).aw.bits.len
  blackbox.io.s00_axi_awsize  <> io.slave(0).aw.bits.size
  blackbox.io.s00_axi_awburst <> io.slave(0).aw.bits.burst
  blackbox.io.s00_axi_awlock  <> io.slave(0).aw.bits.lock
  blackbox.io.s00_axi_awcache <> io.slave(0).aw.bits.cache
  blackbox.io.s00_axi_awprot  <> io.slave(0).aw.bits.prot
  blackbox.io.s00_axi_awqos   <> io.slave(0).aw.bits.qos
  blackbox.io.s00_axi_awuser  <> io.slave(0).aw.bits.user
  blackbox.io.s00_axi_awvalid <> io.slave(0).aw.valid
  blackbox.io.s00_axi_awready <> io.slave(0).aw.ready
  blackbox.io.s00_axi_wdata   <> io.slave(0).w.bits.data
  blackbox.io.s00_axi_wstrb   <> io.slave(0).w.bits.strb
  blackbox.io.s00_axi_wlast   <> io.slave(0).w.bits.last
  blackbox.io.s00_axi_wuser   <> io.slave(0).w.bits.user
  blackbox.io.s00_axi_wvalid  <> io.slave(0).w.valid
  blackbox.io.s00_axi_wready  <> io.slave(0).w.ready
  blackbox.io.s00_axi_bid     <> io.slave(0).b.bits.id
  blackbox.io.s00_axi_bresp   <> io.slave(0).b.bits.resp
  blackbox.io.s00_axi_buser   <> io.slave(0).b.bits.user
  blackbox.io.s00_axi_bvalid  <> io.slave(0).b.valid
  blackbox.io.s00_axi_bready  <> io.slave(0).b.ready
  blackbox.io.s00_axi_arid    <> io.slave(0).ar.bits.id
  blackbox.io.s00_axi_araddr  <> io.slave(0).ar.bits.addr
  blackbox.io.s00_axi_arlen   <> io.slave(0).ar.bits.len
  blackbox.io.s00_axi_arsize  <> io.slave(0).ar.bits.size
  blackbox.io.s00_axi_arburst <> io.slave(0).ar.bits.burst
  blackbox.io.s00_axi_arlock  <> io.slave(0).ar.bits.lock
  blackbox.io.s00_axi_arcache <> io.slave(0).ar.bits.cache
  blackbox.io.s00_axi_arprot  <> io.slave(0).ar.bits.prot
  blackbox.io.s00_axi_arqos   <> io.slave(0).ar.bits.qos
  blackbox.io.s00_axi_aruser  <> io.slave(0).ar.bits.user
  blackbox.io.s00_axi_arvalid <> io.slave(0).ar.valid
  blackbox.io.s00_axi_arready <> io.slave(0).ar.ready
  blackbox.io.s00_axi_rid     <> io.slave(0).r.bits.id
  blackbox.io.s00_axi_rdata   <> io.slave(0).r.bits.data
  blackbox.io.s00_axi_rresp   <> io.slave(0).r.bits.resp
  blackbox.io.s00_axi_rlast   <> io.slave(0).r.bits.last
  blackbox.io.s00_axi_ruser   <> io.slave(0).r.bits.user
  blackbox.io.s00_axi_rvalid  <> io.slave(0).r.valid
  blackbox.io.s00_axi_rready  <> io.slave(0).r.ready

  blackbox.io.s01_axi_awid    <> io.slave(1).aw.bits.id
  blackbox.io.s01_axi_awaddr  <> io.slave(1).aw.bits.addr
  blackbox.io.s01_axi_awlen   <> io.slave(1).aw.bits.len
  blackbox.io.s01_axi_awsize  <> io.slave(1).aw.bits.size
  blackbox.io.s01_axi_awburst <> io.slave(1).aw.bits.burst
  blackbox.io.s01_axi_awlock  <> io.slave(1).aw.bits.lock
  blackbox.io.s01_axi_awcache <> io.slave(1).aw.bits.cache
  blackbox.io.s01_axi_awprot  <> io.slave(1).aw.bits.prot
  blackbox.io.s01_axi_awqos   <> io.slave(1).aw.bits.qos
  blackbox.io.s01_axi_awuser  <> io.slave(1).aw.bits.user
  blackbox.io.s01_axi_awvalid <> io.slave(1).aw.valid
  blackbox.io.s01_axi_awready <> io.slave(1).aw.ready
  blackbox.io.s01_axi_wdata   <> io.slave(1).w.bits.data
  blackbox.io.s01_axi_wstrb   <> io.slave(1).w.bits.strb
  blackbox.io.s01_axi_wlast   <> io.slave(1).w.bits.last
  blackbox.io.s01_axi_wuser   <> io.slave(1).w.bits.user
  blackbox.io.s01_axi_wvalid  <> io.slave(1).w.valid
  blackbox.io.s01_axi_wready  <> io.slave(1).w.ready
  blackbox.io.s01_axi_bid     <> io.slave(1).b.bits.id
  blackbox.io.s01_axi_bresp   <> io.slave(1).b.bits.resp
  blackbox.io.s01_axi_buser   <> io.slave(1).b.bits.user
  blackbox.io.s01_axi_bvalid  <> io.slave(1).b.valid
  blackbox.io.s01_axi_bready  <> io.slave(1).b.ready
  blackbox.io.s01_axi_arid    <> io.slave(1).ar.bits.id
  blackbox.io.s01_axi_araddr  <> io.slave(1).ar.bits.addr
  blackbox.io.s01_axi_arlen   <> io.slave(1).ar.bits.len
  blackbox.io.s01_axi_arsize  <> io.slave(1).ar.bits.size
  blackbox.io.s01_axi_arburst <> io.slave(1).ar.bits.burst
  blackbox.io.s01_axi_arlock  <> io.slave(1).ar.bits.lock
  blackbox.io.s01_axi_arcache <> io.slave(1).ar.bits.cache
  blackbox.io.s01_axi_arprot  <> io.slave(1).ar.bits.prot
  blackbox.io.s01_axi_arqos   <> io.slave(1).ar.bits.qos
  blackbox.io.s01_axi_aruser  <> io.slave(1).ar.bits.user
  blackbox.io.s01_axi_arvalid <> io.slave(1).ar.valid
  blackbox.io.s01_axi_arready <> io.slave(1).ar.ready
  blackbox.io.s01_axi_rid     <> io.slave(1).r.bits.id
  blackbox.io.s01_axi_rdata   <> io.slave(1).r.bits.data
  blackbox.io.s01_axi_rresp   <> io.slave(1).r.bits.resp
  blackbox.io.s01_axi_rlast   <> io.slave(1).r.bits.last
  blackbox.io.s01_axi_ruser   <> io.slave(1).r.bits.user
  blackbox.io.s01_axi_rvalid  <> io.slave(1).r.valid
  blackbox.io.s01_axi_rready  <> io.slave(1).r.ready

  blackbox.io.s02_axi_awid    <> io.slave(2).aw.bits.id
  blackbox.io.s02_axi_awaddr  <> io.slave(2).aw.bits.addr
  blackbox.io.s02_axi_awlen   <> io.slave(2).aw.bits.len
  blackbox.io.s02_axi_awsize  <> io.slave(2).aw.bits.size
  blackbox.io.s02_axi_awburst <> io.slave(2).aw.bits.burst
  blackbox.io.s02_axi_awlock  <> io.slave(2).aw.bits.lock
  blackbox.io.s02_axi_awcache <> io.slave(2).aw.bits.cache
  blackbox.io.s02_axi_awprot  <> io.slave(2).aw.bits.prot
  blackbox.io.s02_axi_awqos   <> io.slave(2).aw.bits.qos
  blackbox.io.s02_axi_awuser  <> io.slave(2).aw.bits.user
  blackbox.io.s02_axi_awvalid <> io.slave(2).aw.valid
  blackbox.io.s02_axi_awready <> io.slave(2).aw.ready
  blackbox.io.s02_axi_wdata   <> io.slave(2).w.bits.data
  blackbox.io.s02_axi_wstrb   <> io.slave(2).w.bits.strb
  blackbox.io.s02_axi_wlast   <> io.slave(2).w.bits.last
  blackbox.io.s02_axi_wuser   <> io.slave(2).w.bits.user
  blackbox.io.s02_axi_wvalid  <> io.slave(2).w.valid
  blackbox.io.s02_axi_wready  <> io.slave(2).w.ready
  blackbox.io.s02_axi_bid     <> io.slave(2).b.bits.id
  blackbox.io.s02_axi_bresp   <> io.slave(2).b.bits.resp
  blackbox.io.s02_axi_buser   <> io.slave(2).b.bits.user
  blackbox.io.s02_axi_bvalid  <> io.slave(2).b.valid
  blackbox.io.s02_axi_bready  <> io.slave(2).b.ready
  blackbox.io.s02_axi_arid    <> io.slave(2).ar.bits.id
  blackbox.io.s02_axi_araddr  <> io.slave(2).ar.bits.addr
  blackbox.io.s02_axi_arlen   <> io.slave(2).ar.bits.len
  blackbox.io.s02_axi_arsize  <> io.slave(2).ar.bits.size
  blackbox.io.s02_axi_arburst <> io.slave(2).ar.bits.burst
  blackbox.io.s02_axi_arlock  <> io.slave(2).ar.bits.lock
  blackbox.io.s02_axi_arcache <> io.slave(2).ar.bits.cache
  blackbox.io.s02_axi_arprot  <> io.slave(2).ar.bits.prot
  blackbox.io.s02_axi_arqos   <> io.slave(2).ar.bits.qos
  blackbox.io.s02_axi_aruser  <> io.slave(2).ar.bits.user
  blackbox.io.s02_axi_arvalid <> io.slave(2).ar.valid
  blackbox.io.s02_axi_arready <> io.slave(2).ar.ready
  blackbox.io.s02_axi_rid     <> io.slave(2).r.bits.id
  blackbox.io.s02_axi_rdata   <> io.slave(2).r.bits.data
  blackbox.io.s02_axi_rresp   <> io.slave(2).r.bits.resp
  blackbox.io.s02_axi_rlast   <> io.slave(2).r.bits.last
  blackbox.io.s02_axi_ruser   <> io.slave(2).r.bits.user
  blackbox.io.s02_axi_rvalid  <> io.slave(2).r.valid
  blackbox.io.s02_axi_rready  <> io.slave(2).r.ready

  blackbox.io.m00_axi_awid     <> io.master(0).aw.bits.id
  blackbox.io.m00_axi_awaddr   <> io.master(0).aw.bits.addr
  blackbox.io.m00_axi_awlen    <> io.master(0).aw.bits.len
  blackbox.io.m00_axi_awsize   <> io.master(0).aw.bits.size
  blackbox.io.m00_axi_awburst  <> io.master(0).aw.bits.burst
  blackbox.io.m00_axi_awlock   <> io.master(0).aw.bits.lock
  blackbox.io.m00_axi_awcache  <> io.master(0).aw.bits.cache
  blackbox.io.m00_axi_awprot   <> io.master(0).aw.bits.prot
  blackbox.io.m00_axi_awqos    <> io.master(0).aw.bits.qos
  blackbox.io.m00_axi_awregion <> io.master(0).aw.bits.region
  blackbox.io.m00_axi_awuser   <> io.master(0).aw.bits.user
  blackbox.io.m00_axi_awvalid  <> io.master(0).aw.valid
  blackbox.io.m00_axi_awready  <> io.master(0).aw.ready
  blackbox.io.m00_axi_wdata    <> io.master(0).w.bits.data
  blackbox.io.m00_axi_wstrb    <> io.master(0).w.bits.strb
  blackbox.io.m00_axi_wlast    <> io.master(0).w.bits.last
  blackbox.io.m00_axi_wuser    <> io.master(0).w.bits.user
  blackbox.io.m00_axi_wvalid   <> io.master(0).w.valid
  blackbox.io.m00_axi_wready   <> io.master(0).w.ready
  blackbox.io.m00_axi_bid      <> io.master(0).b.bits.id
  blackbox.io.m00_axi_bresp    <> io.master(0).b.bits.resp
  blackbox.io.m00_axi_buser    <> io.master(0).b.bits.user
  blackbox.io.m00_axi_bvalid   <> io.master(0).b.valid
  blackbox.io.m00_axi_bready   <> io.master(0).b.ready
  blackbox.io.m00_axi_arid     <> io.master(0).ar.bits.id
  blackbox.io.m00_axi_araddr   <> io.master(0).ar.bits.addr
  blackbox.io.m00_axi_arlen    <> io.master(0).ar.bits.len
  blackbox.io.m00_axi_arsize   <> io.master(0).ar.bits.size
  blackbox.io.m00_axi_arburst  <> io.master(0).ar.bits.burst
  blackbox.io.m00_axi_arlock   <> io.master(0).ar.bits.lock
  blackbox.io.m00_axi_arcache  <> io.master(0).ar.bits.cache
  blackbox.io.m00_axi_arprot   <> io.master(0).ar.bits.prot
  blackbox.io.m00_axi_arqos    <> io.master(0).ar.bits.qos
  blackbox.io.m00_axi_arregion <> io.master(0).ar.bits.region
  blackbox.io.m00_axi_aruser   <> io.master(0).ar.bits.user
  blackbox.io.m00_axi_arvalid  <> io.master(0).ar.valid
  blackbox.io.m00_axi_arready  <> io.master(0).ar.ready
  blackbox.io.m00_axi_rid      <> io.master(0).r.bits.id
  blackbox.io.m00_axi_rdata    <> io.master(0).r.bits.data
  blackbox.io.m00_axi_rresp    <> io.master(0).r.bits.resp
  blackbox.io.m00_axi_rlast    <> io.master(0).r.bits.last
  blackbox.io.m00_axi_ruser    <> io.master(0).r.bits.user
  blackbox.io.m00_axi_rvalid   <> io.master(0).r.valid
  blackbox.io.m00_axi_rready   <> io.master(0).r.ready
}

class axi_3x1_crossbar
    extends BlackBox(
      Map(
        "ADDR_WIDTH" -> spec.Width.Axi.addr.get,
        "DATA_WIDTH" -> spec.Param.Width.Axi.data.get
      )
    ) {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())

    val s00_axi_awid    = Input(UInt(Param.Width.Axi.slaveId))
    val s00_axi_awaddr  = Input(UInt(spec.Width.Axi.addr))
    val s00_axi_awlen   = Input(UInt(8.W))
    val s00_axi_awsize  = Input(UInt(3.W))
    val s00_axi_awburst = Input(UInt(2.W))
    val s00_axi_awlock  = Input(Bool())
    val s00_axi_awcache = Input(UInt(4.W))
    val s00_axi_awprot  = Input(UInt(3.W))
    val s00_axi_awqos   = Input(UInt(4.W))
    val s00_axi_awuser  = Input(UInt(Param.Width.Axi.awuser))
    val s00_axi_awvalid = Input(Bool())
    val s00_axi_awready = Output(Bool())
    val s00_axi_wdata   = Input(UInt(Param.Width.Axi.data))
    val s00_axi_wstrb   = Input(UInt(Param.Width.Axi.strb))
    val s00_axi_wlast   = Input(Bool())
    val s00_axi_wuser   = Input(UInt(Param.Width.Axi.wuser))
    val s00_axi_wvalid  = Input(Bool())
    val s00_axi_wready  = Output(Bool())
    val s00_axi_bid     = Output(UInt(Param.Width.Axi.slaveId))
    val s00_axi_bresp   = Output(UInt(2.W))
    val s00_axi_buser   = Output(UInt(Param.Width.Axi.buser))
    val s00_axi_bvalid  = Output(Bool())
    val s00_axi_bready  = Input(Bool())
    val s00_axi_arid    = Input(UInt(Param.Width.Axi.slaveId))
    val s00_axi_araddr  = Input(UInt(spec.Width.Axi.addr))
    val s00_axi_arlen   = Input(UInt(8.W))
    val s00_axi_arsize  = Input(UInt(3.W))
    val s00_axi_arburst = Input(UInt(2.W))
    val s00_axi_arlock  = Input(Bool())
    val s00_axi_arcache = Input(UInt(4.W))
    val s00_axi_arprot  = Input(UInt(3.W))
    val s00_axi_arqos   = Input(UInt(3.W))
    val s00_axi_aruser  = Input(UInt(Param.Width.Axi.aruser))
    val s00_axi_arvalid = Input(Bool())
    val s00_axi_arready = Output(Bool())
    val s00_axi_rid     = Output(UInt(Param.Width.Axi.slaveId))
    val s00_axi_rdata   = Output(UInt(Param.Width.Axi.data))
    val s00_axi_rresp   = Output(UInt(2.W))
    val s00_axi_rlast   = Output(Bool())
    val s00_axi_ruser   = Output(UInt(Param.Width.Axi.ruser))
    val s00_axi_rvalid  = Output(Bool())
    val s00_axi_rready  = Input(Bool())

    val s01_axi_awid    = Input(UInt(Param.Width.Axi.slaveId))
    val s01_axi_awaddr  = Input(UInt(spec.Width.Axi.addr))
    val s01_axi_awlen   = Input(UInt(8.W))
    val s01_axi_awsize  = Input(UInt(3.W))
    val s01_axi_awburst = Input(UInt(2.W))
    val s01_axi_awlock  = Input(Bool())
    val s01_axi_awcache = Input(UInt(4.W))
    val s01_axi_awprot  = Input(UInt(3.W))
    val s01_axi_awqos   = Input(UInt(4.W))
    val s01_axi_awuser  = Input(UInt(Param.Width.Axi.awuser))
    val s01_axi_awvalid = Input(Bool())
    val s01_axi_awready = Output(Bool())
    val s01_axi_wdata   = Input(UInt(Param.Width.Axi.data))
    val s01_axi_wstrb   = Input(UInt(Param.Width.Axi.strb))
    val s01_axi_wlast   = Input(Bool())
    val s01_axi_wuser   = Input(UInt(Param.Width.Axi.wuser))
    val s01_axi_wvalid  = Input(Bool())
    val s01_axi_wready  = Output(Bool())
    val s01_axi_bid     = Output(UInt(Param.Width.Axi.slaveId))
    val s01_axi_bresp   = Output(UInt(2.W))
    val s01_axi_buser   = Output(UInt(Param.Width.Axi.buser))
    val s01_axi_bvalid  = Output(Bool())
    val s01_axi_bready  = Input(Bool())
    val s01_axi_arid    = Input(UInt(Param.Width.Axi.slaveId))
    val s01_axi_araddr  = Input(UInt(spec.Width.Axi.addr))
    val s01_axi_arlen   = Input(UInt(8.W))
    val s01_axi_arsize  = Input(UInt(3.W))
    val s01_axi_arburst = Input(UInt(2.W))
    val s01_axi_arlock  = Input(Bool())
    val s01_axi_arcache = Input(UInt(4.W))
    val s01_axi_arprot  = Input(UInt(3.W))
    val s01_axi_arqos   = Input(UInt(3.W))
    val s01_axi_aruser  = Input(UInt(Param.Width.Axi.aruser))
    val s01_axi_arvalid = Input(Bool())
    val s01_axi_arready = Output(Bool())
    val s01_axi_rid     = Output(UInt(Param.Width.Axi.slaveId))
    val s01_axi_rdata   = Output(UInt(Param.Width.Axi.data))
    val s01_axi_rresp   = Output(UInt(2.W))
    val s01_axi_rlast   = Output(Bool())
    val s01_axi_ruser   = Output(UInt(Param.Width.Axi.ruser))
    val s01_axi_rvalid  = Output(Bool())
    val s01_axi_rready  = Input(Bool())

    val s02_axi_awid    = Input(UInt(Param.Width.Axi.slaveId))
    val s02_axi_awaddr  = Input(UInt(spec.Width.Axi.addr))
    val s02_axi_awlen   = Input(UInt(8.W))
    val s02_axi_awsize  = Input(UInt(3.W))
    val s02_axi_awburst = Input(UInt(2.W))
    val s02_axi_awlock  = Input(Bool())
    val s02_axi_awcache = Input(UInt(4.W))
    val s02_axi_awprot  = Input(UInt(3.W))
    val s02_axi_awqos   = Input(UInt(4.W))
    val s02_axi_awuser  = Input(UInt(Param.Width.Axi.awuser))
    val s02_axi_awvalid = Input(Bool())
    val s02_axi_awready = Output(Bool())
    val s02_axi_wdata   = Input(UInt(Param.Width.Axi.data))
    val s02_axi_wstrb   = Input(UInt(Param.Width.Axi.strb))
    val s02_axi_wlast   = Input(Bool())
    val s02_axi_wuser   = Input(UInt(Param.Width.Axi.wuser))
    val s02_axi_wvalid  = Input(Bool())
    val s02_axi_wready  = Output(Bool())
    val s02_axi_bid     = Output(UInt(Param.Width.Axi.slaveId))
    val s02_axi_bresp   = Output(UInt(2.W))
    val s02_axi_buser   = Output(UInt(Param.Width.Axi.buser))
    val s02_axi_bvalid  = Output(Bool())
    val s02_axi_bready  = Input(Bool())
    val s02_axi_arid    = Input(UInt(Param.Width.Axi.slaveId))
    val s02_axi_araddr  = Input(UInt(spec.Width.Axi.addr))
    val s02_axi_arlen   = Input(UInt(8.W))
    val s02_axi_arsize  = Input(UInt(3.W))
    val s02_axi_arburst = Input(UInt(2.W))
    val s02_axi_arlock  = Input(Bool())
    val s02_axi_arcache = Input(UInt(4.W))
    val s02_axi_arprot  = Input(UInt(3.W))
    val s02_axi_arqos   = Input(UInt(3.W))
    val s02_axi_aruser  = Input(UInt(Param.Width.Axi.aruser))
    val s02_axi_arvalid = Input(Bool())
    val s02_axi_arready = Output(Bool())
    val s02_axi_rid     = Output(UInt(Param.Width.Axi.slaveId))
    val s02_axi_rdata   = Output(UInt(Param.Width.Axi.data))
    val s02_axi_rresp   = Output(UInt(2.W))
    val s02_axi_rlast   = Output(Bool())
    val s02_axi_ruser   = Output(UInt(Param.Width.Axi.ruser))
    val s02_axi_rvalid  = Output(Bool())
    val s02_axi_rready  = Input(Bool())

    val m00_axi_awid     = Output(UInt(Param.Width.Axi.masterId))
    val m00_axi_awaddr   = Output(UInt(spec.Width.Axi.addr))
    val m00_axi_awlen    = Output(UInt(8.W))
    val m00_axi_awsize   = Output(UInt(3.W))
    val m00_axi_awburst  = Output(UInt(2.W))
    val m00_axi_awlock   = Output(Bool())
    val m00_axi_awcache  = Output(UInt(4.W))
    val m00_axi_awprot   = Output(UInt(3.W))
    val m00_axi_awqos    = Output(UInt(4.W))
    val m00_axi_awregion = Output(UInt(4.W))
    val m00_axi_awuser   = Output(UInt(Param.Width.Axi.awuser))
    val m00_axi_awvalid  = Output(Bool())
    val m00_axi_awready  = Input(Bool())
    val m00_axi_wdata    = Output(UInt(Param.Width.Axi.data))
    val m00_axi_wstrb    = Output(UInt(Param.Width.Axi.strb))
    val m00_axi_wlast    = Output(Bool())
    val m00_axi_wuser    = Output(UInt(Param.Width.Axi.wuser))
    val m00_axi_wvalid   = Output(Bool())
    val m00_axi_wready   = Input(Bool())
    val m00_axi_bid      = Input(UInt(Param.Width.Axi.masterId))
    val m00_axi_bresp    = Input(UInt(2.W))
    val m00_axi_buser    = Input(UInt(Param.Width.Axi.buser))
    val m00_axi_bvalid   = Input(Bool())
    val m00_axi_bready   = Output(Bool())
    val m00_axi_arid     = Output(UInt(Param.Width.Axi.masterId))
    val m00_axi_araddr   = Output(UInt(spec.Width.Axi.addr))
    val m00_axi_arlen    = Output(UInt(8.W))
    val m00_axi_arsize   = Output(UInt(3.W))
    val m00_axi_arburst  = Output(UInt(2.W))
    val m00_axi_arlock   = Output(Bool())
    val m00_axi_arcache  = Output(UInt(4.W))
    val m00_axi_arprot   = Output(UInt(3.W))
    val m00_axi_arqos    = Output(UInt(3.W))
    val m00_axi_arregion = Output(UInt(4.W))
    val m00_axi_aruser   = Output(UInt(Param.Width.Axi.aruser))
    val m00_axi_arvalid  = Output(Bool())
    val m00_axi_arready  = Input(Bool())
    val m00_axi_rid      = Input(UInt(Param.Width.Axi.masterId))
    val m00_axi_rdata    = Input(UInt(Param.Width.Axi.data))
    val m00_axi_rresp    = Input(UInt(2.W))
    val m00_axi_rlast    = Input(Bool())
    val m00_axi_ruser    = Input(UInt(Param.Width.Axi.ruser))
    val m00_axi_rvalid   = Input(Bool())
    val m00_axi_rready   = Output(Bool())
  })
}
