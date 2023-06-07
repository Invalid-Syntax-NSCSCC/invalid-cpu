package frontend

import axi.AxiMaster
import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.InstInfoBundle
import spec.Param.{SimpleFetchStageState => State}
import spec._

class SimpleFetchStage extends Module {
  val io = IO(new Bundle {
    val pc                 = Input(UInt(Width.Reg.data))
    val isNextPc           = Output(Bool())
    val axiMasterInterface = new AxiMasterInterface
    val instEnqueuePort    = Decoupled(new InstInfoBundle)
    // val pipelineControlPort = Input(new PipelineControlNdPort)
    val isFlush = Input(Bool())
  })

  val axiMaster = Module(new AxiMaster)
  io.axiMasterInterface <> axiMaster.io.axi
  axiMaster.io.we       := false.B
  axiMaster.io.uncached := true.B
  axiMaster.io.size     := 4.U
  axiMaster.io.dataIn   := 0.U
  axiMaster.io.wstrb    := 0.U

  val axiReady     = WireInit(axiMaster.io.readyOut)
  val axiReadValid = WireInit(axiMaster.io.validOut)
  val axiData      = WireInit(axiMaster.io.dataOut)

  val nextState = WireInit(State.idle)
  val stateReg  = RegNext(nextState, State.idle)

  val isNextPcReg = RegInit(false.B)
  io.isNextPc := isNextPcReg
  val axiReadRequestReg = RegInit(false.B)
  axiMaster.io.newRequest := axiReadRequestReg
  val axiAddrReg = RegInit(0.U(Width.Axi.addr))
  axiMaster.io.addr := axiAddrReg

  val lastPcReg = RegInit(zeroWord)

  // Fallback
  isNextPcReg              := false.B
  axiReadRequestReg        := false.B
  axiAddrReg               := axiAddrReg
  io.instEnqueuePort.valid := false.B
  io.instEnqueuePort.bits  := DontCare
  lastPcReg                := lastPcReg

  switch(stateReg) {
    is(State.idle) {
      nextState := State.requestInst
    }
    is(State.requestInst) { // State Value: 1
      when(
        axiReady && io.instEnqueuePort.ready && !io.isFlush
      ) {
        nextState := State.waitInst

        isNextPcReg       := true.B
        axiReadRequestReg := true.B
        axiAddrReg        := io.pc
        lastPcReg         := io.pc
      }.otherwise {
        nextState := State.requestInst
      }
    }
    is(State.waitInst) { // State Value: 2
      when(axiReadValid) {
        nextState := State.requestInst
        when(!io.isFlush) {
          io.instEnqueuePort.valid       := true.B
          io.instEnqueuePort.bits.inst   := axiData
          io.instEnqueuePort.bits.pcAddr := lastPcReg
        }
      }
    }
  }
}
