package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import memory.bundles.MemRequestNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import spec._

class MemReqStage extends Module {
  val io = IO(new Bundle {
    val translatedMemRequestPort = Input(new MemRequestNdPort) // <-- AddrTransStage
    val dCacheRequestPort        = Output(new MemRequestNdPort) // --> DCache
    val uncachedRequestPort      = Output(new MemRequestNdPort) // --> UncachedAgent

    // (Next clock pulse)
    val isHasRequest            = Output(Bool())
    val isCachedAccess          = new PassThroughPort(Bool())
    val gprWritePassThroughPort = new PassThroughPort(new RfWriteNdPort)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)
  })

  // Pass GPR write request to the next stage
  val gprWriteReg = RegNext(io.gprWritePassThroughPort.in)
  io.gprWritePassThroughPort.out := gprWriteReg

  // Pass whether is cached access to the next stage
  val isCachedReg = RegNext(io.isCachedAccess.in)
  io.isCachedAccess.out := isCachedReg

  // Pass whether has request to the next stage
  val isHasRequestReg = RegNext(false.B, false.B) // Fallback: No request
  io.isHasRequest := isHasRequestReg

  // Wb debug port connection
  val instInfoReg = RegNext(io.instInfoPassThroughPort.in)
  io.instInfoPassThroughPort.out := instInfoReg

  // Fallback: Do not send request
  io.dCacheRequestPort           := DontCare
  io.uncachedRequestPort         := DontCare
  io.dCacheRequestPort.isValid   := false.B
  io.uncachedRequestPort.isValid := false.B

  // Send request
  when(io.translatedMemRequestPort.isValid) {
    // TODO: Doesn't take account stall
    isHasRequestReg := true.B
  }
  when(io.isCachedAccess.in) {
    io.dCacheRequestPort := io.translatedMemRequestPort
  }.otherwise {
    io.uncachedRequestPort := io.translatedMemRequestPort
  }
}
