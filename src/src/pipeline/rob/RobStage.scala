package pipeline.rob

import chisel3._
import chisel3.util._
import common.bundles.RfWriteNdPort
import pipeline.rob.bundles.RobIdDistributePort
import pipeline.rob.bundles.RobInstStoreBundle
import pipeline.rob.enums.{RobInstState => State}
import utils._
import spec.wordLength
import utils.BiCounter
import pipeline.dataforward.bundles.ReadPortWithValid

// TODO: flush
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

  /** Store buffer
    */

  val idCounter = RegInit(1.U(idLength.W))
  val buffer    = RegInit(VecInit(Seq.fill(robLength)(RobInstStoreBundle.default)))

  val enq_ptr = Module(new BiCounter(robLength))
  val deq_ptr = Module(new BiCounter(robLength))
  Seq(enq_ptr, deq_ptr).foreach { ptr =>
    ptr.io.inc   := 0.U
    ptr.io.flush := false.B
  }

  val maybeFull = RegInit(false.B)
  val ptrMatch  = enq_ptr.io.value === deq_ptr.io.value
  val isEmpty   = ptrMatch && !maybeFull
  val isFull    = ptrMatch && maybeFull

  val storeNum = WireDefault(
    Mux(
      enq_ptr.io.value > deq_ptr.io.value,
      enq_ptr.io.value - deq_ptr.io.value,
      (robLength.U - deq_ptr.io.value) + enq_ptr.io.value
    )
  )
  val emptyNum = WireDefault(robLength.U - storeNum)

  val isEmptyByOne = WireDefault(storeNum === 1.U)
  val isFullByOne  = WireDefault(emptyNum === 1.U)

  io.emptyNum := emptyNum

  /** id distribute
    */

  io.idDistributePorts(0).id := idCounter
  io.idDistributePorts(1).id := idCounter + 1.U
  idCounter                  := idCounter + 2.U

  val instWriteNum = WireDefault(
    io.idDistributePorts.map(_.writeEn.asUInt).reduce(_ +& _)
  )

  val enqPtrIncOneResult = WireDefault(enq_ptr.io.incOneResult)
  val enqPtrIncTwoResult = WireDefault(enq_ptr.io.incTwoResult)
  when(isFullByOne) {
    // 只能分配一个
    when(io.idDistributePorts(0).writeEn) {
      enq_ptr.io.inc                   := 1.U
      buffer(enqPtrIncOneResult).state := State.busy
      buffer(enqPtrIncOneResult).id    := io.idDistributePorts(0).id
    }.elsewhen(io.idDistributePorts(1).writeEn) {
      enq_ptr.io.inc                   := 1.U
      buffer(enqPtrIncOneResult).state := State.busy
      buffer(enqPtrIncOneResult).id    := io.idDistributePorts(1).id
    }
  }.elsewhen(!isFull) {
    // 能分配2个
    when(io.idDistributePorts(0).writeEn) {
      enq_ptr.io.inc                   := 1.U
      buffer(enqPtrIncOneResult).state := State.busy
      buffer(enqPtrIncOneResult).id    := io.idDistributePorts(0).id
      when(io.idDistributePorts(1).writeEn) {
        enq_ptr.io.inc                   := 2.U
        buffer(enqPtrIncTwoResult).state := State.busy
        buffer(enqPtrIncTwoResult).id    := io.idDistributePorts(1).id
      }
    }.elsewhen(io.idDistributePorts(1).writeEn) {
      enq_ptr.io.inc                   := 1.U
      buffer(enqPtrIncOneResult).state := State.busy
      buffer(enqPtrIncOneResult).id    := io.idDistributePorts(1).id
    }
  }

  /** write ready
    */
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

  /** read
    */
  io.readPorts.foreach { readPort =>
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
