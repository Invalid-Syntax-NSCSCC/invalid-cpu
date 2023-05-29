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
  })

  io.instEnqueuePort.bits.pcAddr := io.pc
  io.instEnqueuePort.bits.inst   := io.iCacheAccessPort.res.read.data

  val stateReg = RegInit(State.idle)
  stateReg := stateReg

  val shouldDiscardReg = RegInit(false.B) // Fallback: Follow
  val shouldDiscard    = WireInit(io.isFlush || shouldDiscardReg)

  // Fallbacks
  io.isPcNext                            := false.B
  io.iCacheAccessPort.req.client.isValid := false.B
  io.iCacheAccessPort.req.client.addr    := io.pc
  io.instEnqueuePort.valid               := false.B

  switch(stateReg) {
    is(State.idle) { // State Value: 0
      stateReg := State.request
    }
    is(State.request) { // State Value: 1
      when(io.iCacheAccessPort.req.isReady) {
        stateReg                               := State.waitQueue
        io.iCacheAccessPort.req.client.isValid := true.B
      }
    }

    is(State.waitQueue) { // State Value: 2
      shouldDiscardReg := shouldDiscard
      when(io.iCacheAccessPort.res.isComplete) {
        io.instEnqueuePort.valid := true.B
        when(shouldDiscard) {
          io.instEnqueuePort.valid := false.B
          stateReg         := State.request
          shouldDiscardReg := false.B
        }.elsewhen(io.instEnqueuePort.ready) {
          stateReg         := State.request
          shouldDiscardReg := false.B
          io.isPcNext      := true.B

          when(io.iCacheAccessPort.req.isReady) {
            stateReg                               := State.waitQueue
            io.iCacheAccessPort.req.client.addr    := Mux(shouldDiscard, io.pc, io.pc + 4.U)
            io.iCacheAccessPort.req.client.isValid := true.B
          }.otherwise {
            stateReg := State.request
          }
        }
      }
    }
  }
}
