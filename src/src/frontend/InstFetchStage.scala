package frontend

import chisel3._
import chisel3.util._
import frontend.bundles.ICacheAccessPort
import pipeline.dispatch.bundles.InstInfoBundle
import spec.Param.{NaiiveFetchStageState => State}
import spec._

class InstFetchStage extends Module {
  val io = IO(new Bundle {
    val pc       = Input(UInt(Width.Reg.data))
    val isPcNext = Output(Bool())

    // <-> Frontend  <->ICache
    val accessPort = Flipped(new ICacheAccessPort)

    // <-> Frontend <-> Instrution queue
    val isFlush         = Input(Bool())
    val instEnqueuePort = Decoupled(new InstInfoBundle)
  })

  io.instEnqueuePort.bits := InstInfoBundle.default

  io.instEnqueuePort.bits.pcAddr := io.pc
  io.instEnqueuePort.bits.inst   := io.accessPort.res.read.data

  val stateReg = RegInit(State.idle)
  stateReg := stateReg

  val shouldDiscardReg = RegInit(false.B) // Fallback: Follow
  val shouldDiscard    = WireInit(io.isFlush || shouldDiscardReg)

  val isCompleteReg = RegInit(false.B)
  isCompleteReg := isCompleteReg
  val lastInstReg = RegInit(0.U(Width.inst))
  lastInstReg := lastInstReg

  // Fallbacks
  io.isPcNext                      := false.B
  io.accessPort.req.client.isValid := false.B
  io.accessPort.req.client.addr    := io.pc
  io.instEnqueuePort.valid         := false.B

  switch(stateReg) {
    is(State.idle) { // State Value: 0
      stateReg := State.request
    }
    is(State.request) { // State Value: 1
      when(io.accessPort.req.isReady) {
        stateReg                         := State.waitQueue
        io.accessPort.req.client.isValid := true.B
        isCompleteReg                    := false.B
      }
    }

    is(State.waitQueue) { // State Value: 2
      shouldDiscardReg := shouldDiscard
      when(io.accessPort.res.isComplete) {
        isCompleteReg := true.B
        lastInstReg   := io.accessPort.res.read.data
      }
      when(isCompleteReg) {
        io.instEnqueuePort.bits.inst := lastInstReg
      }
      when(io.accessPort.res.isComplete || isCompleteReg) {
        io.instEnqueuePort.valid := true.B
        when(shouldDiscard) {
          io.instEnqueuePort.valid := false.B
          stateReg                 := State.request
          shouldDiscardReg         := false.B
        }.elsewhen(io.instEnqueuePort.ready) {
          stateReg         := State.request
          shouldDiscardReg := false.B
          io.isPcNext      := true.B

          when(io.accessPort.req.isReady) {
            stateReg                         := State.waitQueue
            io.accessPort.req.client.addr    := Mux(shouldDiscard, io.pc, io.pc + 4.U)
            io.accessPort.req.client.isValid := true.B
            isCompleteReg                    := false.B
          }.otherwise {
            stateReg := State.request
          }
        }
      }
    }
  }
}
