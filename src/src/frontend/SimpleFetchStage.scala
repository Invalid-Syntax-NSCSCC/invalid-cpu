package frontend

import axi.AxiMaster
import axi.bundles.AxiMasterPort
import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.InstInfoBundle
import spec._
import Param.{SimpleFetchStageState => State}

import java.lang.Package

class SimpleFetchStage extends Module {
  val io = IO(new Bundle {
    val pc                 = Input(UInt(Width.Reg.data))
    val isPcNext           = Output(Bool())
    val axiMasterInterface = Flipped(new AxiMasterPort)
    val instEnqueuePort    = Decoupled(new InstInfoBundle)
  })

  val axiMaster = Module(new AxiMaster)
  axiMaster.io.axi <> io.axiMasterInterface
  axiMaster.io.we       := false.B
  axiMaster.io.uncached := true.B
  axiMaster.io.size     := 4.U
  axiMaster.io.dataIn   := 0.U
  axiMaster.io.wstrb    := 0.U

  val axiReady     = WireInit(axiMaster.io.readyOut)
  val axiReadValid = WireInit(axiMaster.io.validOut)
  val axiData      = WireInit(axiMaster.io.dataOut)

  val nextState = WireInit(State.idle)
  val state     = RegNext(nextState, State.idle)

  val isPcNextReg = RegInit(false.B)
  io.isPcNext := isPcNextReg
  val axiReadRequestReg = RegInit(false.B)
  axiMaster.io.newRequest := axiReadRequestReg
  val axiAddrReg = RegInit(0.U(Width.Axi.addr))
  axiMaster.io.addr := axiAddrReg

  // Fallback
  isPcNextReg              := false.B
  axiReadRequestReg        := false.B
  axiAddrReg               := axiAddrReg
  io.instEnqueuePort.valid := false.B
  io.instEnqueuePort.bits  := DontCare

  switch(state) {
    is(State.idle) {
      nextState := State.requestInst
    }
    is(State.requestInst) {
      when(axiReady && io.instEnqueuePort.ready) {
        nextState := State.waitInst

        isPcNextReg       := true.B
        axiReadRequestReg := true.B
        axiAddrReg        := io.pc
      }.otherwise {
        nextState := State.requestInst
      }
    }
    is(State.waitInst) {
      when(axiReadValid) {
        nextState := State.requestInst

        io.instEnqueuePort.valid := true.B
        io.instEnqueuePort.bits  := axiData
      }.otherwise {
        nextState := State.waitInst
      }
    }
  }
}
