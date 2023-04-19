package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import memory.bundles.MemAccessNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import spec._

class MemReqStage extends Module {
  val io = IO(new Bundle {
    val translatedMemAccessPort = Input(new MemAccessNdPort)
    val isCachedAccess          = Input(Bool())
    val dCacheRequestPort       = Output(new MemAccessNdPort)
    val uncachedRequestPort     = Output(new MemAccessNdPort)

    // (Next clock pulse)
    val gprWritePassThroughPort = new PassThroughPort(new RfWriteNdPort)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)
  })

  // Pass GPR write request to the next stage
  val gprWriteReg = RegNext(io.gprWritePassThroughPort.in)
  io.gprWritePassThroughPort.out := gprWriteReg

  // Wb debug port connection
  val instInfoReg = RegNext(io.instInfoPassThroughPort.in)
  io.instInfoPassThroughPort.out := instInfoReg

  // Fallback: Do not send request
  io.dCacheRequestPort           := DontCare
  io.uncachedRequestPort         := DontCare
  io.dCacheRequestPort.isValid   := false.B
  io.uncachedRequestPort.isValid := false.B

  // Send request
  when(io.isCachedAccess) {
    io.dCacheRequestPort := io.translatedMemAccessPort
  }.otherwise {
    io.uncachedRequestPort := io.translatedMemAccessPort
  }
}
