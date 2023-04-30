package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec._
import pipeline.dispatch.enums.{PrfState => State}
import pipeline.dispatch.bundles.RenameRequestNdPort
import pipeline.dispatch.bundles.RenameResultNdPort
import utils.BiPriorityMux
import utils.BiCounter
import control.bundles.PipelineControlNDPort

// 重命名+计分板，仅供乱序发射使用,顺序发射时不需要
class Rename(
  arfRegNum: Int = Count.reg,
  prfRegNum: Int = 64,
  changeNum: Int = Param.scoreboardChangeNum,
  occupyNum: Int = Param.regFileWriteNum,
  renameNum: Int = 2)
    extends Module {

  val arfNumLog: Int = log2Ceil(arfRegNum)
  val prfNumLog: Int = log2Ceil(prfRegNum)
  val io = IO(new Bundle {
    val occupyPorts = Input(Vec(occupyNum, new ScoreboardChangeNdPort(prfNumLog.W)))
    val freePorts   = Input(Vec(changeNum, new ScoreboardChangeNdPort(prfNumLog.W)))
    val regScores   = Output(Vec(prfRegNum, State()))

    val renameRequestPorts = Input(Vec(renameNum, new RenameRequestNdPort))
    val renameResultPorts  = Output(Vec(renameNum, new RenameResultNdPort))

    val pipelineControlPort = Input(new PipelineControlNDPort)
  })

  require(renameNum == 2)

  io <> DontCare

  /** State Transform
    *
    * empty ---rename request write------------> busy busy ---------write back----------------> retire retire --next prf
    * ( -> same arf) commit--> empyt
    */

  val arfToPrfMap = RegInit(VecInit(Seq.range(0, arfRegNum).map(_.U(prfNumLog.W))))

  val prfPrevMap = RegInit(VecInit(Seq.fill(prfRegNum)(0.U(prfNumLog.W))))

  val prfStateReg = RegInit(VecInit(Seq.range(0, prfRegNum).map { index =>
    if (index < arfRegNum) {
      State.retire
    } else {
      State.free
    }
  }))

  io.regScores.zip(prfStateReg).foreach {
    case (dst, src) =>
      dst := src
  }

  /** free queue
    */

  val freeQueueLength = prfRegNum - arfRegNum
  val freeQueue = RegInit(
    VecInit(
      Seq.range(arfRegNum, prfRegNum).map {
        _.U(prfNumLog.W)
      }
    )
  )

  val enq_ptr = Module(new BiCounter(freeQueueLength))
  val deq_ptr = Module(new BiCounter(freeQueueLength))
  enq_ptr.io.inc   := 0.U
  enq_ptr.io.flush := io.pipelineControlPort.flush
  deq_ptr.io.inc   := 0.U
  deq_ptr.io.flush := io.pipelineControlPort.flush

  val maybeFull = RegInit(true.B)
  val ptrMatch  = enq_ptr.io.value === deq_ptr.io.value
  val isEmpty   = ptrMatch && !maybeFull
  val isFull    = ptrMatch && maybeFull

  val storeNum = WireDefault(
    Mux(
      enq_ptr.io.value > deq_ptr.io.value,
      enq_ptr.io.value - deq_ptr.io.value,
      (freeQueueLength.U - deq_ptr.io.value) + enq_ptr.io.value
    )
  )
  val emptyNum = WireDefault(freeQueueLength.U - storeNum)

  val isEmptyByOne = WireDefault(storeNum === 1.U)
  val isFullByOne  = WireDefault(emptyNum === 1.U)

  /** Rename Request
    *
    * empty --> free
    */

  val deqRequest = WireDefault(VecInit(io.renameRequestPorts.map { port =>
    (port.arfWritePort.en && port.arfWritePort.addr.orR)
  }))
  val deqNum = WireDefault(deqRequest.map(_.asUInt).reduce(_ +& _))

  io.renameResultPorts.foreach(RenameResultNdPort setDefault _)
  when(!isEmpty) {
    when(isEmptyByOne) {
      // 只能重命名一个
      when(deqRequest(0)) {}
    }
  }

}
