package frontend

import axi.bundles.AxiMasterPort
import axi.{AxiCrossbar, AxiMaster}
import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.InstInfoBundle
import spec.Param.{SimpleFetchStageState => State}
import spec._

class SimpleFetchStage extends Module {
  val io = IO(new Bundle {
    val pc                 = Input(UInt(Width.Reg.data))
    val isPcNext           = Output(Bool())
    val axiMasterInterface = new AxiMasterPort
    val instEnqueuePort    = Decoupled(new InstInfoBundle)
  })

  val axiCrossbar = Module(new AxiCrossbar)
  axiCrossbar.io.slaves                       <> DontCare
  axiCrossbar.io.masters(0).read.r.bits.user  <> DontCare
  axiCrossbar.io.masters(0).write.b.bits.user <> DontCare
  io.axiMasterInterface.arid                  <> axiCrossbar.io.masters(0).read.ar.bits.id
  io.axiMasterInterface.araddr                <> axiCrossbar.io.masters(0).read.ar.bits.addr
  io.axiMasterInterface.arlen                 <> axiCrossbar.io.masters(0).read.ar.bits.len
  io.axiMasterInterface.arsize                <> axiCrossbar.io.masters(0).read.ar.bits.size
  io.axiMasterInterface.arburst               <> axiCrossbar.io.masters(0).read.ar.bits.burst
  io.axiMasterInterface.arlock                <> axiCrossbar.io.masters(0).read.ar.bits.lock
  io.axiMasterInterface.arcache               <> axiCrossbar.io.masters(0).read.ar.bits.cache
  io.axiMasterInterface.arprot                <> axiCrossbar.io.masters(0).read.ar.bits.prot
  io.axiMasterInterface.arvalid               <> axiCrossbar.io.masters(0).read.ar.valid
  io.axiMasterInterface.arready               <> axiCrossbar.io.masters(0).read.ar.ready
  io.axiMasterInterface.rid                   <> axiCrossbar.io.masters(0).read.r.bits.id
  io.axiMasterInterface.rdata                 <> axiCrossbar.io.masters(0).read.r.bits.data
  io.axiMasterInterface.rresp                 <> axiCrossbar.io.masters(0).read.r.bits.resp
  io.axiMasterInterface.rlast                 <> axiCrossbar.io.masters(0).read.r.bits.last
  io.axiMasterInterface.rvalid                <> axiCrossbar.io.masters(0).read.r.valid
  io.axiMasterInterface.rready                <> axiCrossbar.io.masters(0).read.r.ready
  io.axiMasterInterface.awid                  <> axiCrossbar.io.masters(0).write.aw.bits.id
  io.axiMasterInterface.awaddr                <> axiCrossbar.io.masters(0).write.aw.bits.addr
  io.axiMasterInterface.awlen                 <> axiCrossbar.io.masters(0).write.aw.bits.len
  io.axiMasterInterface.awsize                <> axiCrossbar.io.masters(0).write.aw.bits.size
  io.axiMasterInterface.awburst               <> axiCrossbar.io.masters(0).write.aw.bits.burst
  io.axiMasterInterface.awlock                <> axiCrossbar.io.masters(0).write.aw.bits.lock
  io.axiMasterInterface.awcache               <> axiCrossbar.io.masters(0).write.aw.bits.cache
  io.axiMasterInterface.awprot                <> axiCrossbar.io.masters(0).write.aw.bits.prot
  io.axiMasterInterface.awvalid               <> axiCrossbar.io.masters(0).write.aw.valid
  io.axiMasterInterface.awready               <> axiCrossbar.io.masters(0).write.aw.ready
  io.axiMasterInterface.wid                   <> DontCare
  io.axiMasterInterface.wdata                 <> axiCrossbar.io.masters(0).write.w.bits.data
  io.axiMasterInterface.wstrb                 <> axiCrossbar.io.masters(0).write.w.bits.strb
  io.axiMasterInterface.wlast                 <> axiCrossbar.io.masters(0).write.w.bits.last
  io.axiMasterInterface.wvalid                <> axiCrossbar.io.masters(0).write.w.valid
  io.axiMasterInterface.wready                <> axiCrossbar.io.masters(0).write.w.ready
  io.axiMasterInterface.bid                   <> axiCrossbar.io.masters(0).write.b.bits.id
  io.axiMasterInterface.bresp                 <> axiCrossbar.io.masters(0).write.b.bits.resp
  io.axiMasterInterface.bvalid                <> axiCrossbar.io.masters(0).write.b.valid
  io.axiMasterInterface.bready                <> axiCrossbar.io.masters(0).write.b.ready

  val axiMaster = Module(new AxiMaster)
  axiMaster.io.axi.arid    <> axiCrossbar.io.slaves(0).read.ar.bits.id
  axiMaster.io.axi.araddr  <> axiCrossbar.io.slaves(0).read.ar.bits.addr
  axiMaster.io.axi.arlen   <> axiCrossbar.io.slaves(0).read.ar.bits.len
  axiMaster.io.axi.arsize  <> axiCrossbar.io.slaves(0).read.ar.bits.size
  axiMaster.io.axi.arburst <> axiCrossbar.io.slaves(0).read.ar.bits.burst
  axiMaster.io.axi.arlock  <> axiCrossbar.io.slaves(0).read.ar.bits.lock
  axiMaster.io.axi.arcache <> axiCrossbar.io.slaves(0).read.ar.bits.cache
  axiMaster.io.axi.arprot  <> axiCrossbar.io.slaves(0).read.ar.bits.prot
  axiMaster.io.axi.arvalid <> axiCrossbar.io.slaves(0).read.ar.valid
  axiMaster.io.axi.arready <> axiCrossbar.io.slaves(0).read.ar.ready
  axiMaster.io.axi.rid     <> axiCrossbar.io.slaves(0).read.r.bits.id
  axiMaster.io.axi.rdata   <> axiCrossbar.io.slaves(0).read.r.bits.data
  axiMaster.io.axi.rresp   <> axiCrossbar.io.slaves(0).read.r.bits.resp
  axiMaster.io.axi.rlast   <> axiCrossbar.io.slaves(0).read.r.bits.last
  axiMaster.io.axi.rvalid  <> axiCrossbar.io.slaves(0).read.r.valid
  axiMaster.io.axi.rready  <> axiCrossbar.io.slaves(0).read.r.ready
  axiMaster.io.axi.awid    <> axiCrossbar.io.slaves(0).write.aw.bits.id
  axiMaster.io.axi.awaddr  <> axiCrossbar.io.slaves(0).write.aw.bits.addr
  axiMaster.io.axi.awlen   <> axiCrossbar.io.slaves(0).write.aw.bits.len
  axiMaster.io.axi.awsize  <> axiCrossbar.io.slaves(0).write.aw.bits.size
  axiMaster.io.axi.awburst <> axiCrossbar.io.slaves(0).write.aw.bits.burst
  axiMaster.io.axi.awlock  <> axiCrossbar.io.slaves(0).write.aw.bits.lock
  axiMaster.io.axi.awcache <> axiCrossbar.io.slaves(0).write.aw.bits.cache
  axiMaster.io.axi.awprot  <> axiCrossbar.io.slaves(0).write.aw.bits.prot
  axiMaster.io.axi.awvalid <> axiCrossbar.io.slaves(0).write.aw.valid
  axiMaster.io.axi.awready <> axiCrossbar.io.slaves(0).write.aw.ready
  axiMaster.io.axi.wid     <> DontCare
  axiMaster.io.axi.wdata   <> axiCrossbar.io.slaves(0).write.w.bits.data
  axiMaster.io.axi.wstrb   <> axiCrossbar.io.slaves(0).write.w.bits.strb
  axiMaster.io.axi.wlast   <> axiCrossbar.io.slaves(0).write.w.bits.last
  axiMaster.io.axi.wvalid  <> axiCrossbar.io.slaves(0).write.w.valid
  axiMaster.io.axi.wready  <> axiCrossbar.io.slaves(0).write.w.ready
  axiMaster.io.axi.bid     <> axiCrossbar.io.slaves(0).write.b.bits.id
  axiMaster.io.axi.bresp   <> axiCrossbar.io.slaves(0).write.b.bits.resp
  axiMaster.io.axi.bvalid  <> axiCrossbar.io.slaves(0).write.b.valid
  axiMaster.io.axi.bready  <> axiCrossbar.io.slaves(0).write.b.ready
  axiMaster.io.we          := false.B
  axiMaster.io.uncached    := true.B
  axiMaster.io.size        := 4.U
  axiMaster.io.dataIn      := 0.U
  axiMaster.io.wstrb       := 0.U

  val axiReady     = WireInit(axiMaster.io.readyOut)
  val axiReadValid = WireInit(axiMaster.io.validOut)
  val axiData      = WireInit(axiMaster.io.dataOut)

  val nextState = WireInit(State.idle)
  val stateReg  = RegNext(nextState, State.idle)

  val isPcNextReg = RegInit(false.B)
  io.isPcNext := isPcNextReg
  val axiReadRequestReg = RegInit(false.B)
  axiMaster.io.newRequest := axiReadRequestReg
  val axiAddrReg = RegInit(0.U(Width.Axi.addr))
  axiMaster.io.addr := axiAddrReg

  val lastPcReg = RegInit(zeroWord)

  // Fallback
  isPcNextReg              := false.B
  axiReadRequestReg        := false.B
  axiAddrReg               := axiAddrReg
  io.instEnqueuePort.valid := false.B
  io.instEnqueuePort.bits  := DontCare
  lastPcReg                := lastPcReg

  switch(stateReg) {
    is(State.idle) {
      nextState := State.requestInst
    }
    is(State.requestInst) {
      when(axiReady && io.instEnqueuePort.ready) {
        nextState := State.waitInst

        isPcNextReg       := true.B
        axiReadRequestReg := true.B
        axiAddrReg        := io.pc
        lastPcReg         := io.pc
      }.otherwise {
        nextState := State.requestInst
      }
    }
    is(State.waitInst) {
      when(axiReadValid) {
        nextState := State.requestInst

        io.instEnqueuePort.valid       := true.B
        io.instEnqueuePort.bits.inst   := axiData
        io.instEnqueuePort.bits.pcAddr := lastPcReg
      }.otherwise {
        nextState := State.waitInst
      }
    }
  }
}
