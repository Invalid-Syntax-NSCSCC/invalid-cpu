package pipeline.dispatch

import chisel3._
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.enums.{ScoreboardState => State}
import spec._

class Scoreboard(
  changeNum: Int = Param.issueInstInfoMaxNum * Param.regFileWriteNum,
  regNum:    Int = Count.reg)
    extends Module {
  val io = IO(new Bundle {
    val occupyPorts = Input(Vec(changeNum, new ScoreboardChangeNdPort))
    val toMemPorts  = Input(Vec(changeNum, new ScoreboardChangeNdPort))
    val freePorts   = Input(Vec(changeNum, new ScoreboardChangeNdPort))
    val regScores   = Output(Vec(regNum, State()))
    val isFlush     = Input(Bool())
    val branchFlush = Input(Bool())
  })

  val isRegOccupied = RegInit(VecInit(Seq.fill(Count.reg)(State.free)))
  io.regScores.zip(isRegOccupied).foreach {
    case (dest, reg) =>
      dest := reg
  }

  isRegOccupied.zipWithIndex.foreach {
    case (reg, index) =>
      reg := reg
      if (index == 0) {
        // GPR 0 is not meant to be written and always keeps 0
        reg := State.free
      } else {
        when(io.toMemPorts.map(port => port.en && port.addr === index.U).reduce(_ || _)) {
          reg := State.afterExe
        }.elsewhen(io.occupyPorts.map(port => port.en && port.addr === index.U).reduce(_ || _)) {
          reg := State.beforeExe
        }.elsewhen(io.freePorts.map(port => port.en && port.addr === index.U).reduce(_ || _)) {
          reg := State.free
        }
      }
  }

  when(io.isFlush) {
    isRegOccupied.foreach(_ := State.free)
  }.elsewhen(io.branchFlush) {
    isRegOccupied.foreach { reg =>
      when(reg === State.beforeExe) {
        reg := State.free
      }
    }
  }
}
