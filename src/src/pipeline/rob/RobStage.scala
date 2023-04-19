package pipeline.rob

import chisel3._
import chisel3.util._
import common.bundles.RfWriteNdPort
import pipeline.rob.bundles.RobIdDistributePort
import pipeline.rob.bundles.RobInstStoreBundle
import pipeline.rob.enums.{RobInstState => State}
import utils._

// 重排序缓冲区，未接入cpu
class RobStage(
  robLength: Int = 8,
  issueNum:  Int = 2,
  commitNum: Int = 2,
  idLength:  Int = 32 // 分配id长度
) extends Module {
  val io = IO(new Bundle {
    val robLengthLog      = log2Ceil(robLength)
    val emptyNum          = Output(UInt(robLengthLog.W))
    val idDistributePorts = Vec(issueNum, new RobIdDistributePort(idLength = idLength))
    val writeReadyPorts   = Input(Vec(issueNum, new RfWriteNdPort))
    val instIds           = Input(Vec(commitNum, 0.U(idLength.W)))
    val commitPorts       = Output(Vec(issueNum, new RfWriteNdPort))
  })
  require(issueNum == 2)

  io.idDistributePorts.foreach(_.id := 0.U)
  io.commitPorts.foreach(_ := RfWriteNdPort.default)

  val counter = RegInit(1.U(idLength.W))
  val buffer  = RegInit(VecInit(Seq.fill(robLength)(new RobInstStoreBundle)))

  // commit 有逻辑错误，应选id最小的输出
  val areReady    = WireDefault(VecInit(buffer.map(_.state === State.ready)))
  val biCommitMux = Module(new BiPriorityMux(robLength))
  biCommitMux.io.inVector := areReady

  biCommitMux.io.selectIndices.zip(io.commitPorts).foreach {
    case (commitIndexInfo, port) =>
      when(commitIndexInfo.valid) {
        port                                := buffer(commitIndexInfo.index).writePort
        buffer(commitIndexInfo.index).state := State.empty
      }
  }

  // id distribute 考虑一下加入同时写入和commit的判断
  val areEmpty = WireDefault(VecInit(buffer.map(_.state === State.empty)))
  val emptyNum = WireDefault(areEmpty.count(_.asBool))
  io.emptyNum := emptyNum

  val biWriteMux = Module(new BiPriorityMux(robLength))
  biWriteMux.io.inVector := areEmpty

  when(emptyNum === 1.U) {
    // 只能分配一个
    when(io.idDistributePorts(0).writeEn) {
      io.idDistributePorts(0).id                         := counter
      buffer(biWriteMux.io.selectIndices(0).index).id    := counter
      buffer(biWriteMux.io.selectIndices(0).index).state := State.busy
      counter                                            := counter + 1.U
    }.elsewhen(io.idDistributePorts(1).writeEn) {
      io.idDistributePorts(1).id                         := counter
      buffer(biWriteMux.io.selectIndices(0).index).id    := counter
      buffer(biWriteMux.io.selectIndices(0).index).state := State.busy
      counter                                            := counter + 1.U
    }
  }.elsewhen(emptyNum =/= 0.U) {
    // 2个都能分配
    io.idDistributePorts(0).id                         := counter
    io.idDistributePorts(1).id                         := counter + 1.U
    buffer(biWriteMux.io.selectIndices(0).index).id    := counter
    buffer(biWriteMux.io.selectIndices(0).index).state := State.busy
    buffer(biWriteMux.io.selectIndices(1).index).id    := counter + 1.U
    buffer(biWriteMux.io.selectIndices(1).index).state := State.busy
    counter                                            := counter + 2.U
  }
}
