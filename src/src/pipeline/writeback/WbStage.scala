package pipeline.writeback

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.writeback.bundles.WbDebugNdPort
import spec._

class WbStage(changeNum: Int = Param.scoreboardChangeNum) extends Module {
  val io = IO(new Bundle {
    val gprWriteInfoPort = Input(new RfWriteNdPort)
    val gprWritePort     = Output(new RfWriteNdPort)

    // Scoreboard
    val freePorts = Output(Vec(changeNum, new ScoreboardChangeNdPort))

    val wbDebugPassthroughPort = new PassThroughPort(new WbDebugNdPort)
  })

  // Wb debug port connection
  io.wbDebugPassthroughPort.out := io.wbDebugPassthroughPort.in

  io.gprWritePort := io.gprWriteInfoPort

  // Indicate the availability in scoreboard
  io.freePorts.zip(Seq(io.gprWritePort)).foreach {
    case (freePort, accessInfo) =>
      freePort.en   := accessInfo.en
      freePort.addr := accessInfo.addr
  }
}
