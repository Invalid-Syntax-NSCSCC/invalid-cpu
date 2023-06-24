package pipeline.dispatch

import chisel3._
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.enums.{ScoreboardState => State}

class CsrScoreboard extends Module {
  val io = IO(new Bundle {
    val occupyPort  = Input(new ScoreboardChangeNdPort)
    val toMemPort   = Input(new ScoreboardChangeNdPort)
    val freePort    = Input(new ScoreboardChangeNdPort)
    val regScore    = Output(State())
    val isFlush     = Input(Bool())
    val branchFlush = Input(Bool())
  })

  val isRegOccupied = RegInit(State.free)
  isRegOccupied := isRegOccupied
  io.regScore   := isRegOccupied

  when(io.freePort.en) {
    isRegOccupied := State.free
  }.elsewhen(io.toMemPort.en) {
    isRegOccupied := State.afterExe
  }.elsewhen(io.occupyPort.en) {
    isRegOccupied := State.beforeExe
  }

  when(io.isFlush) {
    isRegOccupied := State.free
  }.elsewhen(io.branchFlush) {
    when(isRegOccupied === State.beforeExe) {
      isRegOccupied := State.free
    }
  }
}
