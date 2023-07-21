package pipeline.dispatch

import chisel3._
import chisel3.util.Valid
import control.bundles.CsrWriteNdPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.enums.{ScoreboardState => State}

class CsrScoreboard extends Module {
  val io = IO(new Bundle {
    val csrWriteStorePort = Input(Valid(new CsrWriteNdPort))
    val csrWritePort      = Output(new CsrWriteNdPort)

    val isFlush = Input(Bool())
  })

  val isWriteValid = RegInit(true.B)

  val csrWriteReg = RegInit(CsrWriteNdPort.default)
  io.csrWritePort := csrWriteReg

  when(io.isFlush) {
    isWriteValid   := true.B
    csrWriteReg.en := false.B
  }.elsewhen(io.csrWriteStorePort.valid && isWriteValid) {
    isWriteValid := false.B
    csrWriteReg  := io.csrWriteStorePort.bits
  }

}
