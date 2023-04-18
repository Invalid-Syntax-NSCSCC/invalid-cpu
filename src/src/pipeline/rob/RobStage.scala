package pipeline.rob

import chisel3._
import chisel3.util._
import common.bundles.RfWriteNdPort
import pipeline.rob.bundles.RobIdDistributePort
import pipeline.rob.bundles.RobInstStoreBundle
import pipeline.rob.enums.{RobInstStage => Stage}
import frontend.BiCounter

// 重排序缓冲区，未接入cpu
class RobStage(
  robLength: Int = 8,
  issueNum:  Int = 2,
  commitNum: Int = 2,
  idLength:  Int = 32 // 分配id长度
) extends Module {
  val io = IO(new Bundle {
    val idDistributePort = Vec(issueNum, new RobIdDistributePort(idLength = idLength))
    val writePorts       = Input(Vec(issueNum, new RfWriteNdPort))
    val instIds          = Input(Vec(commitNum, 0.U(idLength.W)))
    val commitPorts      = Output(Vec(issueNum, new RfWriteNdPort))
  })
  require(issueNum == 2)

  io.idDistributePort.foreach(_.id := 0.U)
  io.commitPorts.foreach(_ := RfWriteNdPort.default)

  val counter = RegInit(1.U(idLength.W))
  val buffer  = RegInit(VecInit(Seq.fill(robLength)(new RobInstStoreBundle)))

  val areEmpty = WireDefault(VecInit(buffer.map(_.state === Stage.empty)))
  val areReady = WireDefault(VecInit(buffer.map(_.state === Stage.ready)))

  val biWriteMux = Module(new BiPriorityMux(robLength))
  biWriteMux.io.inVector := areEmpty

  val biCommitMux = Module(new BiPriorityMux(robLength))
  biCommitMux.io.inVector := areReady

  biCommitMux.io.selectIndices.zip(io.commitPorts).foreach {
    case (commitIndexInfo, port) =>
      when(commitIndexInfo.valid) {
        port                                := buffer(commitIndexInfo.index).writePort
        buffer(commitIndexInfo.index).state := Stage.empty
      }
  }

}
