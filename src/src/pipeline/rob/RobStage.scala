package pipeline.rob

import chisel3._
import chisel3.util._
import common.bundles.RfWriteNdPort
import pipeline.rob.bundles.RobIdDistributePort
import pipeline.rob.bundles.RobInstStoreBundle
import pipeline.rob.enums.{RobInstStage => Stage}

// 重排序缓冲区，未接入cpu
class RobStage(
  robLength: Int = 8,
  issueNum:  Int = 2,
  commitNum: Int = 2,
  idLength:  Int = 32 // 分配id长度
) extends Module {
  val io = IO(new Bundle {
    val idDistributePort = Vec(issueNum, new RobIdDistributePort(idLength = idLength))
    val enqueuePorts     = Input(Vec(issueNum, new RfWriteNdPort))
    val commit           = Output(Vec(issueNum, new RfWriteNdPort))
  })
  require(issueNum == 2)

  val buffer = RegInit(VecInit(Seq.fill(robLength)(new RobInstStoreBundle)))

  val counter = RegInit(1.U(idLength.W))

  io.idDistributePort.foreach(_.id := 0.U)
  io.commit.foreach(_ := RfWriteNdPort.default)

  val emptyIndex = WireDefault(VecInit(buffer.map(_.state === Stage.empty)))
}
