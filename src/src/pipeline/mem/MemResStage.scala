package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import control.bundles.PipelineControlNdPort
import memory.bundles.MemResponseNdPort
import pipeline.mem.enums.{MemResStageState => State}
import pipeline.writeback.bundles.InstInfoNdPort
import spec._

import scala.collection.immutable

class MemResStage extends Module {
  val io = IO(new Bundle {
    val dCacheResponsePort   = Input(new MemResponseNdPort) // <-- DCache
    val uncachedResponsePort = Input(new MemResponseNdPort) // <-- UncachedAgent
    val isHasRequest         = Input(Bool()) // <-- MemReqStage
    val isCachedRequest      = Input(Bool()) // <-- MemReqStage
    val isUnsigned           = Input(Bool()) // <-- MemReqStage
    val dataMask             = Input(UInt((Width.Mem._data / byteLength).W)) // <-- MemReqStage
    val pipelineControlPort  = Input(new PipelineControlNdPort) // <-- Cu
    val stallRequest         = Output(Bool()) // --> Cu

    // (Next clock pulse)
    val gprWritePassThroughPort = new PassThroughPort(new RfWriteNdPort)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)
  })

  // Pass GPR write request to the next stage
  val gprWriteReg = RegNext(io.gprWritePassThroughPort.in)
  io.gprWritePassThroughPort.out := gprWriteReg // Fallback: Pass through

  // Wb debug port connection
  val instInfoReg = RegNext(io.instInfoPassThroughPort.in)
  io.instInfoPassThroughPort.out := instInfoReg // Fallback: Pass through

  // State
  val stateReg = RegInit(State.free)

  // Persist information
  val lastGprWriteReg = RegInit(RfWriteNdPort.default)
  lastGprWriteReg := lastGprWriteReg // Fallback: Keep data

  val lastInstInfoReg = RegInit(InstInfoNdPort.default)
  lastInstInfoReg := lastInstInfoReg // Fallback: Keep data

  // Select complete signal
  val isComplete = WireDefault(
    Mux(
      io.isCachedRequest,
      io.dCacheResponsePort.isComplete,
      io.uncachedResponsePort.isComplete
    )
  )

  // Select read data
  val rawReadData = WireDefault(
    Mux(
      io.isCachedRequest,
      io.dCacheResponsePort.read.data,
      io.uncachedResponsePort.read.data
    )
  )
  val signedReadData   = WireDefault(0.S(Width.Mem.data))
  val unsignedReadData = WireDefault(0.U(Width.Mem.data))
  def readDataLookup[T <: Bits](modifier: UInt => T) = MuxLookup(io.dataMask, modifier(rawReadData))(
    Seq
      .range(0, 4)
      .map(index => (1.U << index).asUInt -> modifier(rawReadData((index + 1) * 8 - 1, index * 8))) ++
      Seq
        .range(0, 2)
        .map(index =>
          ("b11".U << index * 2).asUInt -> modifier(
            rawReadData((index + 1) * wordLength / 2 - 1, index * wordLength / 2)
          )
        ) ++
      Seq("b1111".U -> modifier(rawReadData))
  )
  signedReadData   := readDataLookup(_.asSInt)
  unsignedReadData := readDataLookup(_.asUInt)
  val readData = Mux(io.isUnsigned, unsignedReadData, signedReadData.asUInt)

  // Fallback: Do not stall
  io.stallRequest := false.B

  switch(stateReg) {
    is(State.free) {
      lastGprWriteReg := io.gprWritePassThroughPort.in
      lastInstInfoReg := io.instInfoPassThroughPort.in

      when(io.isHasRequest) {
        // Check whether hit when has request
        when(isComplete) {
          // When hit
          // TODO: Handle failing (which might not be necessary)
          io.gprWritePassThroughPort.out.data := readData
        }.otherwise {
          // When not hit
          io.gprWritePassThroughPort.out.en := false.B
          InstInfoNdPort.invalidate(io.instInfoPassThroughPort.out)

          io.stallRequest := true.B

          // Next state: Wait for hit
          stateReg := State.busy
        }
      }
    }
    is(State.busy) {
      when(isComplete) {
        // Continue to write-back stage
        io.gprWritePassThroughPort.out      := lastGprWriteReg
        io.gprWritePassThroughPort.out.data := readData
        io.instInfoPassThroughPort.out      := lastInstInfoReg

        // Next state: Ready
        stateReg := State.free
      }.otherwise {
        // No GPR write and invalidate instruction
        io.gprWritePassThroughPort.out.en := false.B
        InstInfoNdPort.invalidate(io.instInfoPassThroughPort.out)

        io.stallRequest := true.B
      }
    }
  }

  // Flush
  when(io.pipelineControlPort.flush) {
    gprWriteReg.en := false.B
    InstInfoNdPort.invalidate(instInfoReg)
  }
}
