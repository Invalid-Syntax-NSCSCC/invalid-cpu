package frontend

import axi.AxiMaster
import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util._
import control.bundles.PipelineControlNdPort
import pipeline.dispatch.bundles.InstInfoBundle
import spec.Param.{NaiiveFetchStageState => State}
import spec._
import frontend.bundles.ICacheAccessPort

class InstFetchStage extends Module {

  val io = IO(new Bundle {

    val pc       = Input(UInt(Width.Reg.data))
    val isPcNext = Output(Bool())

    // <-> Frontend  <->ICache
    val iCacheAccessPort = Flipped(new ICacheAccessPort)

    // <-> Frontend <-> Instrution queue
    val isFlush         = Input(Bool())
    val instEnqueuePort = Decoupled(new InstInfoBundle)

    // val instQueueAccessPort = new Bundle{
    //     val isFlush      = Input(Bool())
    //     val instEnqueuePort    = Decoupled(new InstInfoBundle)
    //     // val instEnqueuePorts = Vec(Param.Count.frontend.instFetchNum, Flipped(Decoupled(new InstInfoBundle)))

    // }
  })

  val iCacheAccessPort = new ICacheAccessPort

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
  val isInstValid      = WireInit(io.iCacheAccessPort.res.isComplete || isInstValidReg)

  // Fallbacks
  io.isPcNext                            := false.B
  io.iCacheAccessPort.req.client.addr    := pcReg
  io.instEnqueuePort.valid               := false.B
  pc                                     := pcReg
  inst                                   := instReg
  pcReg                                  := pcReg
  instReg                                := instReg
  shouldDiscardReg                       := false.B
  isInstValidReg                         := false.B
  io.iCacheAccessPort.req.client.isValid := false.B

  switch(stateReg) {
    is(State.idle) { // State Value: 0
      nextState := State.request
    }
    is(State.request) { // State Value: 1
      when(io.isFlush) {
        nextState := State.waitQueue
      }.otherwise {
        nextState                              := State.waitQueue
        io.isPcNext                            := true.B
        io.iCacheAccessPort.req.client.isValid := true.B
        io.iCacheAccessPort.req.client.addr    := io.pc
        pc                                     := io.pc
        pcReg                                  := io.pc
        shouldDiscardReg                       := false.B
      }

    }

    is(State.waitQueue) { // State Value: 2
      shouldDiscardReg := shouldDiscard
      isInstValidReg   := isInstValid
      when(io.iCacheAccessPort.res.isComplete) {
        inst    := io.iCacheAccessPort.res.read.data
        instReg := io.iCacheAccessPort.res.read.data
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
