package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import control.bundles.PipelineControlNdPort
import pipeline.mem.bundles.MemRequestNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import spec._

class MemReqStage extends Module {
  val io = IO(new Bundle {
    val translatedMemRequestPort = Input(new MemRequestNdPort) // <-- AddrTransStage
    val dCacheRequestPort        = Output(new MemRequestNdPort) // --> DCache
    val uncachedRequestPort      = Output(new MemRequestNdPort) // --> UncachedAgent
    val pipelineControlPort      = Input(new PipelineControlNdPort) // <-- Cu

    // (Next clock pulse)
    val isHasRequest            = Output(Bool())
    val isCachedAccess          = new PassThroughPort(Bool())
    val gprWritePassThroughPort = new PassThroughPort(new RfWriteNdPort)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)
    val isUnsigned              = Output(Bool())
    val dataMask                = Output(UInt((Width.Mem._data / byteLength).W))
  })

  // Persist for stalling
  val isLastStall = RegNext(io.pipelineControlPort.stall, false.B)
  val translatedMemRequestReg =
    RegEnable(io.translatedMemRequestPort, MemRequestNdPort.default, io.pipelineControlPort.stall)
  val selectedMemRequest = Mux(isLastStall, translatedMemRequestReg, io.translatedMemRequestPort)

  // Send to next stage
  val gprWriteReg = RegNext(io.gprWritePassThroughPort.in)
  io.gprWritePassThroughPort.out := gprWriteReg

  // Wb debug port connection
  val instInfoReg = RegNext(io.instInfoPassThroughPort.in)
  io.instInfoPassThroughPort.out := instInfoReg

  val isCachedReg = RegNext(io.isCachedAccess.in)
  io.isCachedAccess.out := isCachedReg

  val isHasRequestReg = RegNext(false.B, false.B) // Fallback: No request
  io.isHasRequest := isHasRequestReg

  val isUnsignedReg = RegNext(selectedMemRequest.read.isUnsigned)
  io.isUnsigned := isUnsignedReg

  val dataMaskReg = RegNext(selectedMemRequest.mask)
  io.dataMask := dataMaskReg

  // Fallback: Do not send request
  io.dCacheRequestPort           := DontCare
  io.uncachedRequestPort         := DontCare
  io.dCacheRequestPort.isValid   := false.B
  io.uncachedRequestPort.isValid := false.B

  // Send request
  when(!io.pipelineControlPort.stall && !io.pipelineControlPort.flush) {
    when(selectedMemRequest.isValid) {
      isHasRequestReg := true.B
    }
    when(io.isCachedAccess.in) {
      io.dCacheRequestPort := selectedMemRequest
    }.otherwise {
      io.uncachedRequestPort := selectedMemRequest
    }
  }

  // Flush
  when(io.pipelineControlPort.flush) {
    gprWriteReg.en := false.B
    InstInfoNdPort.invalidate(instInfoReg)
  }
}
