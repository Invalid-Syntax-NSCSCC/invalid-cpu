package frontend

import axi.AxiMaster
import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.InstInfoBundle
import spec.Param.{NaiiveFetchStageState => State}
import spec._

class NaiiveFetchStage extends Module {
  val io = IO(new Bundle {
    val pc                 = Input(UInt(Width.Reg.data))
    val isPcNext           = Output(Bool())
    val axiMasterInterface = new AxiMasterInterface
    val instEnqueuePort    = Decoupled(new InstInfoBundle)
    val isFlush            = Input(Bool())
  })

  val axiMaster = Module(new AxiMaster)
  axiMaster.io.axi      <> io.axiMasterInterface
  axiMaster.io.we       := false.B
  axiMaster.io.uncached := true.B
  axiMaster.io.size     := 4.U // 32 bits
  axiMaster.io.dataIn   := 0.U
  axiMaster.io.wstrb    := 0.U

  val pcReg   = RegInit(zeroWord)
  val instReg = RegInit(zeroWord)
  val pc      = WireInit(pcReg)
  val inst    = WireInit(instReg)
  io.instEnqueuePort.bits.pcAddr := pc
  io.instEnqueuePort.bits.inst   := inst

  val nextState = WireInit(State.idle)
  val stateReg  = RegNext(nextState, State.idle)

  val shouldDiscardReg = RegInit(false.B)
  val shouldDiscard    = WireInit(io.isFlush || shouldDiscardReg)
  val isInstValidReg   = RegInit(false.B)
  val isInstValid      = WireInit(axiMaster.io.validOut || isInstValidReg)

  // Fallbacks
  io.isPcNext              := false.B
  axiMaster.io.newRequest  := false.B
  axiMaster.io.addr        := pcReg
  io.instEnqueuePort.valid := false.B
  pc                       := pcReg
  inst                     := instReg
  pcReg                    := pcReg
  instReg                  := instReg
  shouldDiscardReg         := false.B
  isInstValidReg           := false.B

  switch(stateReg) {
    is(State.idle) { // State Value: 0
      nextState := State.request
    }
    is(State.request) { // State Value: 1
      when(io.isFlush || !axiMaster.io.readyOut) {
        nextState := State.waitQueue
      }.otherwise {
        nextState := State.waitQueue

        io.isPcNext             := true.B
        axiMaster.io.newRequest := true.B
        axiMaster.io.addr       := io.pc
        pc                      := io.pc
        pcReg                   := io.pc
        shouldDiscardReg        := false.B
      }
    }
    is(State.waitQueue) { // State Value: 2
      shouldDiscardReg := shouldDiscard
      isInstValidReg   := isInstValid
      when(axiMaster.io.validOut) {
        inst    := axiMaster.io.dataOut
        instReg := axiMaster.io.dataOut
      }
      when(!isInstValid || !io.instEnqueuePort.ready) {
        nextState := State.waitQueue
      }.otherwise {
        nextState := State.request // non-stopping fetching instructions
        when(!shouldDiscard) {
          io.instEnqueuePort.valid := true.B
        }
      }
    }
  }
}
