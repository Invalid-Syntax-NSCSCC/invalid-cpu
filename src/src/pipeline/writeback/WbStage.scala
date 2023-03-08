package pipeline.writeback

import chisel3._
import chisel3.util._
import common.bundles.{RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec._

class WbStage(changeNum: Int = Param.scoreboardChangeNum) extends Module {
  val io = IO(new Bundle {
    val gprWriteInfoPort = Input(new RfWriteNdPort)
    val gprWritePort     = Output(new RfWriteNdPort)

    // Scoreboard
    val freePorts = Output(Vec(changeNum, new ScoreboardChangeNdPort))
  })

  io.gprWritePort := io.gprWriteInfoPort

  // Indicate the availability in scoreboard
  io.freePorts.zip(Seq(io.gprWritePort)).foreach {
    case (freePort, accessInfo) =>
      freePort.en   := accessInfo.en
      freePort.addr := accessInfo.addr
  }
}
