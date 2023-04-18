package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import pipeline.execution.bundles.MemLoadStoreInfoNdPort
import control.bundles.PipelineControlNDPort
import pipeline.mem.bundles.MemAccessNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort

class MemStage extends Module {
  val io = IO(new Bundle {
    // `ExeStage` -> `MemStage` -> `WbStage`
    val gprWritePassThroughPort = new PassThroughPort(new RfWriteNdPort)
    // `ExeStage` -> `MemStage`
    val memLoadStoreInfoPort = Input(new MemLoadStoreInfoNdPort)
    // `Cu` -> `MemStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)
    // `MemStage` -> Cu
    val stallRequest     = Output(Bool())
    val memLoadStorePort = Flipped(new MemAccessNdPort)

    // Scoreboard
    val freePorts = Output(new ScoreboardChangeNdPort)

    // (Next clock pulse)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)
  })

  // Wb debug port connection
  val instInfoReg = Reg(new InstInfoNdPort)
  instInfoReg                    := io.instInfoPassThroughPort.in
  io.instInfoPassThroughPort.out := instInfoReg

  val gprWriteReg = RegInit(RfWriteNdPort.default)
  gprWriteReg := Mux(
    io.pipelineControlPort.stall,
    gprWriteReg,
    io.gprWritePassThroughPort.in
  )
  io.gprWritePassThroughPort.out := gprWriteReg

  io.stallRequest := false.B

  io.memLoadStorePort <> DontCare

  // Flush or clear
  when(io.pipelineControlPort.flush || io.pipelineControlPort.clear) {
    gprWriteReg := RfWriteNdPort.default
    InstInfoNdPort.setDefault(instInfoReg)
  }

}
