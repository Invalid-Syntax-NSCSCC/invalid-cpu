package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec._

class CsrScoreboard(
  changeNum: Int = Param.scoreboardChangeNum,
  regNum:    Int = Count.reg,
  occupyNum: Int = Param.regFileWriteNum)
    extends Module {
  val io = IO(new Bundle {
    val occupyPorts = Input(Vec(occupyNum, new ScoreboardChangeNdPort))
    val freePorts   = Input(Vec(changeNum, new ScoreboardChangeNdPort))
    val regScores   = Output(Vec(regNum, Bool()))
  })

  val isRegOccupied = RegInit(VecInit(Seq.fill(regNum)(false.B)))
  io.regScores.zip(isRegOccupied).foreach {
    case (dest, reg) =>
      dest := reg
  }

  isRegOccupied.zip(Csr.Index.addrs).foreach {
    case (reg, addr) =>
      reg := reg

      when(io.occupyPorts.map(port => port.en && port.addr === addr).reduce(_ || _)) {
        reg := true.B
      }.elsewhen(io.freePorts.map(port => port.en && port.addr === addr).reduce(_ || _)) {
        reg := false.B
      }
  }
}
