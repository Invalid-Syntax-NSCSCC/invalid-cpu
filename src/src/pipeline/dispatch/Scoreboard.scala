package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec._

class Scoreboard extends Module {
  val io = IO(new Bundle {
    val occupyPort = Input(new ScoreboardChangeNdPort)
    val freePort   = Input(new ScoreboardChangeNdPort)
    val regScores  = Output(Vec(Count.reg, Bool()))
  })

  val isRegOccupied = RegInit(VecInit(Seq.fill(Count.reg)(false.B)))
  io.regScores.zip(isRegOccupied).foreach {
    case (dest, reg) => dest := reg
  }

  isRegOccupied.zipWithIndex.foreach {
    case (reg, index) =>
      reg := reg
      when(io.occupyPort.en && io.occupyPort.addr === index.U) {
        reg := true.B
      }.elsewhen(io.freePort.en && io.freePort.addr === index.U) {
        reg := false.B
      }
  }
}
