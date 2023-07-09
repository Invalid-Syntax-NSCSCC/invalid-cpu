package pipeline.dispatch

import chisel3._
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.enums.{ScoreboardState => State}
import control.bundles.CsrWriteNdPort
import chisel3.util.Valid

class CsrScoreboard extends Module {
  val io = IO(new Bundle {
    val occupyPort        = Input(new ScoreboardChangeNdPort)
    val toMemPort         = Input(new ScoreboardChangeNdPort)
    val csrWriteStorePort = Input(Valid(new CsrWriteNdPort))
    val csrWritePort      = Output(new CsrWriteNdPort)

    val freePort    = Input(new ScoreboardChangeNdPort)
    val regScore    = Output(State())
    val isFlush     = Input(Bool())
    val branchFlush = Input(Bool())
  })

  val isRegOccupied = RegInit(State.free)
  isRegOccupied := isRegOccupied
  io.regScore   := isRegOccupied

  val csrWriteReg = RegInit(CsrWriteNdPort.default)
  io.csrWritePort := csrWriteReg

  when(io.freePort.en) {
    isRegOccupied  := State.free
    csrWriteReg.en := false.B
  }.elsewhen(io.toMemPort.en) {
    isRegOccupied := State.afterExe
    when(io.csrWriteStorePort.valid) {
      csrWriteReg := io.csrWriteStorePort.bits
    }
  }.elsewhen(io.occupyPort.en) {
    isRegOccupied := State.beforeExe
  }

  when(io.isFlush) {
    isRegOccupied  := State.free
    csrWriteReg.en := false.B
  }.elsewhen(io.branchFlush) {
    when(isRegOccupied === State.beforeExe) {
      isRegOccupied  := State.free
      csrWriteReg.en := false.B
    }
  }
}
