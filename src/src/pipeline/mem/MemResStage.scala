package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import memory.bundles.MemResponseNdPort
import pipeline.mem.enums.{MemResStageState => State}
import pipeline.writeback.bundles.InstInfoNdPort
import spec._

class MemResStage extends Module {
  val io = IO(new Bundle {
    val dCacheResponsePort   = Input(new MemResponseNdPort) // <-- DCache
    val uncachedResponsePort = Input(new MemResponseNdPort) // <-- UncachedAgent
    val isHasRequest         = Input(Bool()) // <-- MemReqStage
    val isCachedRequest      = Input(Bool()) // <-- MemReqStage

    // (Next clock pulse)
    val gprWritePassThroughPort = new PassThroughPort(new RfWriteNdPort)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)
  })

  // TODO: Should need stall if not hit

  // Pass GPR write request to the next stage
  val gprWriteReg = RegNext(io.gprWritePassThroughPort.in)
  io.gprWritePassThroughPort.out := gprWriteReg // Fallback: Pass through

  // Wb debug port connection
  val instInfoReg = RegNext(io.instInfoPassThroughPort.in)
  io.instInfoPassThroughPort.out := instInfoReg

  // State
  val stateReg = RegInit(State.free)

  // Persist information
  val lastGprWriteReg = RegInit(RfWriteNdPort.default)
  lastGprWriteReg := lastGprWriteReg // Fallback: Keep data

  // Select complete signal
  val isComplete = WireDefault(
    Mux(
      io.isCachedRequest,
      io.dCacheResponsePort.isComplete,
      io.uncachedResponsePort.isComplete
    )
  )

  // Select read data
  val readData = WireDefault(
    Mux(
      io.isCachedRequest,
      io.dCacheResponsePort.read.data,
      io.uncachedResponsePort.read.data
    )
  )

  switch(stateReg) {
    is(State.free) {
      when(io.isHasRequest) {
        // Check whether hit when has request
        when(isComplete) {
          // When hit
          // TODO: Handle failing
          io.gprWritePassThroughPort.out.data := readData
        }.otherwise {
          // When not hit
          // TODO: Handle stall
          // TODO: Also stall instruction information
          lastGprWriteReg                   := io.gprWritePassThroughPort.in
          io.gprWritePassThroughPort.out.en := false.B

          // Next state: Wait for hit
          stateReg := State.busy
        }
      }
    }
    is(State.busy) {
      when(isComplete) {
        // Continue to write-back stage
        // TODO: Stop stall
        io.gprWritePassThroughPort.out      := lastGprWriteReg
        io.gprWritePassThroughPort.out.data := readData

        // Next state: Ready
        stateReg := State.free
      }.otherwise {
        // No GPR write
        io.gprWritePassThroughPort.out.en := false.B
      }
    }
  }
}
