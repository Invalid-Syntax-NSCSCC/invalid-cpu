package pipeline.rob

import chisel3._
import chisel3.util._
import common.bundles.RfWriteNdPort
import pipeline.rob.bundles.RobIdDistributePort
import pipeline.rob.bundles.RobInstStoreBundle
import pipeline.rob.enums.{RobInstState => State}
import utils._
import spec.wordLength
import pipeline.dataforward.bundles.ReadPortWithValid

// 重排序缓冲区，未接入cpu
// 只给需要写寄存器的分配id
class RobStage(
  robLength: Int = 16,
  issueNum:  Int = 2,
  commitNum: Int = 2,
  readNum:   Int = 2,
  idLength:  Int = 32 // 分配id长度
) extends Module {
  val robLengthLog = log2Ceil(robLength)
  val io = IO(new Bundle {
    val emptyNum          = Output(UInt(robLengthLog.W))
    val idDistributePorts = Vec(issueNum, new RobIdDistributePort(idLength = idLength))
    val writeReadyPorts   = Input(Vec(issueNum, new RfWriteNdPort))
    val instReadyIds      = Input(Vec(issueNum, UInt(idLength.W)))
    val readPorts         = Vec(readNum, new ReadPortWithValid)
    val commitPorts       = Output(Vec(commitNum, new RfWriteNdPort))
  })
  require(issueNum == 2)

  io.idDistributePorts.foreach(_.id := 0.U)
  io.commitPorts.foreach(_ := RfWriteNdPort.default)

  val counter = RegInit(1.U(idLength.W))
  val buffer  = RegInit(VecInit(Seq.fill(robLength)(RobInstStoreBundle.default)))

  /** Commit
    */
  val areValid = WireDefault(
    VecInit(
      buffer.map { robInstStore => robInstStore.state === State.busy || robInstStore.state === State.ready }
    )
  )
  val firstCommitIndexFinder = Module(new MinFinder(robLength, idLength))
  firstCommitIndexFinder.io.values := WireDefault(VecInit(buffer.map(_.id)))
  firstCommitIndexFinder.io.masks  := areValid

  val secondCommitIndexFinder = Module(new MinFinder(robLength, idLength))
  secondCommitIndexFinder.io.values := WireDefault(VecInit(buffer.map(_.id)))
  val secondAreValid = WireDefault(areValid)
  secondAreValid(firstCommitIndexFinder.io.index) := false.B
  secondCommitIndexFinder.io.masks                := secondAreValid

  when(buffer(firstCommitIndexFinder.io.index).state === State.ready) {
    io.commitPorts(0)                             := buffer(firstCommitIndexFinder.io.index).writePort
    buffer(firstCommitIndexFinder.io.index).state := State.empty
  }

  when(
    buffer(secondCommitIndexFinder.io.index).state === State.ready &&
      secondCommitIndexFinder.io.index =/= firstCommitIndexFinder.io.index
  ) {
    io.commitPorts(1)                              := buffer(secondCommitIndexFinder.io.index).writePort
    buffer(secondCommitIndexFinder.io.index).state := State.empty
  }

  /** id distribute TODO: 加入同时写入和commit的判断
    */
  val areEmpty = WireDefault(VecInit(buffer.map(_.state === State.empty)))
  val emptyNum = WireDefault(areEmpty.count(_.asBool))
  io.emptyNum := emptyNum

  val biWriteMux = Module(new BiPriorityMux(robLength))
  biWriteMux.io.inVector := areEmpty

  io.idDistributePorts(0).id := counter
  io.idDistributePorts(1).id := counter + 1.U
  counter                    := counter + 2.U
  when(emptyNum === 1.U) {
    when(io.idDistributePorts(0).writeEn) {
      buffer(biWriteMux.io.selectIndices(0).index).id    := io.idDistributePorts(0).id
      buffer(biWriteMux.io.selectIndices(0).index).state := State.busy
    }.elsewhen(io.idDistributePorts(1).writeEn) {
      buffer(biWriteMux.io.selectIndices(0).index).id    := io.idDistributePorts(1).id
      buffer(biWriteMux.io.selectIndices(0).index).state := State.busy
    }
  }.elsewhen(emptyNum === 2.U) {
    when(io.idDistributePorts(0).writeEn) {
      buffer(biWriteMux.io.selectIndices(0).index).id    := io.idDistributePorts(0).id
      buffer(biWriteMux.io.selectIndices(0).index).state := State.busy
    }
    when(io.idDistributePorts(1).writeEn) {
      buffer(biWriteMux.io.selectIndices(1).index).id    := io.idDistributePorts(1).id
      buffer(biWriteMux.io.selectIndices(1).index).state := State.busy
    }
  }

  // when(emptyNum === 1.U) {
  //   // 只能分配一个
  //   when(io.idDistributePorts(0).writeEn) {
  //     io.idDistributePorts(0).id                         := counter
  //     buffer(biWriteMux.io.selectIndices(0).index).id    := counter
  //     buffer(biWriteMux.io.selectIndices(0).index).state := State.busy
  //     counter                                            := counter + 1.U
  //   }.elsewhen(io.idDistributePorts(1).writeEn) {
  //     io.idDistributePorts(1).id                         := counter
  //     buffer(biWriteMux.io.selectIndices(0).index).id    := counter
  //     buffer(biWriteMux.io.selectIndices(0).index).state := State.busy
  //     counter                                            := counter + 1.U
  //   }
  // }.elsewhen(emptyNum === 2.U) {
  //   // 2个都能分配
  //   io.idDistributePorts(0).id                         := counter
  //   io.idDistributePorts(1).id                         := counter + 1.U
  //   buffer(biWriteMux.io.selectIndices(0).index).id    := counter
  //   buffer(biWriteMux.io.selectIndices(0).index).state := State.busy
  //   buffer(biWriteMux.io.selectIndices(1).index).id    := counter + 1.U
  //   buffer(biWriteMux.io.selectIndices(1).index).state := State.busy
  //   counter                                            := counter + 2.U
  // }

  // ready
  io.writeReadyPorts.zip(io.instReadyIds).foreach {
    case (readyPort, readyId) => {
      buffer.foreach { robStore =>
        when(robStore.state === State.busy && robStore.id === readyId && readyPort.en) {
          robStore.state     := State.ready
          robStore.writePort := readyPort
        }
      }
    }
  }

  // read
  io.readPorts.foreach { readPort =>
    readPort.valid := false.B
    readPort.data  := DontCare
  }
  io.readPorts.foreach { readPort =>
    // buffer.foreach { robStore =>
    //   when(robStore.state === State.ready && robStore.writePort.en && robStore.writePort.addr === readPort.addr) {
    //     readPort.valid := true.B
    //     readPort.data  := robStore.writePort.data
    //   }
    // }
    val minFinderForRead = Module(new MinFinder(robLength, idLength))
    minFinderForRead.io.values := WireDefault(VecInit(buffer.map(_.id)))
    minFinderForRead.io.masks := WireDefault(
      VecInit(
        buffer.map { robInstStore =>
          robInstStore.writePort.addr === readPort.addr && robInstStore.writePort.en
        }
      )
    )
    val minResult = WireDefault(buffer(minFinderForRead.io.index))
    readPort.valid := minFinderForRead.io.masks.reduce(_ || _) && minResult.state === State.ready
    readPort.data  := minResult.writePort.data
  }
}
