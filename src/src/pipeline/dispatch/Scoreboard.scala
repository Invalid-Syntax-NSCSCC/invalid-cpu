package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec._

class Scoreboard(changeNum: Int = Param.scoreboardChangeNum) extends Module {
  val io = IO(new Bundle {
    val occupyPorts = Input(Vec(changeNum, new ScoreboardChangeNdPort))
    val freePorts   = Input(Vec(changeNum, new ScoreboardChangeNdPort))
    val regScores   = Output(Vec(Count.reg, Bool()))
  })

  val isRegOccupied = RegInit(VecInit(Seq.fill(Count.reg)(false.B)))
  io.regScores.zip(isRegOccupied).foreach {
    case (dest, reg) =>
      dest := reg
  }

  isRegOccupied.zipWithIndex.foreach {
    case (reg, index) =>
      reg := reg
      when(io.occupyPorts.map(port => port.en && port.addr === index.U).reduce(_ || _)) {
        reg := true.B
      }.elsewhen(io.freePorts.map(port => port.en && port.addr === index.U).reduce(_ || _)) {
        reg := false.B
      }
  }
}
