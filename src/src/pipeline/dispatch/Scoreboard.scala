package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec._

class Scoreboard(
  changeNum: Int = Param.scoreboardChangeNum,
  regNum:    Int = Count.reg,
  occupyNum: Int = Param.regFileWriteNum)
    extends Module {
  val io = IO(new Bundle {
    val occupyPorts = Input(Vec(occupyNum, new ScoreboardChangeNdPort))
    val freePorts   = Input(Vec(changeNum, new ScoreboardChangeNdPort))
    val regScores   = Output(Vec(regNum, Bool()))
  })

  val isRegOccupied = RegInit(VecInit(Seq.fill(Count.reg)(false.B)))
  io.regScores.zip(isRegOccupied).foreach {
    case (dest, reg) =>
      dest := reg
  }

  isRegOccupied.zipWithIndex.foreach {
    case (reg, index) =>
      reg := reg
      if (index == 0) {
        // GPR 0 is not meant to be written and always keeps 0
        reg := false.B
      } else {
        when(io.occupyPorts.map(port => port.en && port.addr === index.U).reduce(_ || _)) {
          reg := true.B
        }.elsewhen(io.freePorts.map(port => port.en && port.addr === index.U).reduce(_ || _)) {
          reg := false.B
        }
      }
  }
}
