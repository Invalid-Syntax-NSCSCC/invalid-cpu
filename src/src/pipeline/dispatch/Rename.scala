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
import firrtl.castRhs

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
    * free ---rename request write------------> busy
    *
    * busy ---------write back----------------> retire
    *
    * retire --next prf ( -> same arf) commit--> empty
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

  /** Rename Request From RenameStage
    *
    * free --> busy
    */

  val deqRequest = WireDefault(VecInit(io.renameRequestPorts.map { port =>
    (port.arfWritePort.en && port.arfWritePort.addr.orR)
  }))
  val deqNum = WireDefault(deqRequest.map(_.asUInt).reduce(_ +& _))

  io.renameResultPorts.zip(io.renameRequestPorts).foreach {
    case (dst, src) =>
      dst.grfReadPorts.zip(src.arfReadPorts).foreach {
        case (dstRead, srcRead) =>
          dstRead.en   := srcRead.en
          dstRead.addr := arfToPrfMap(srcRead.addr)
      }
  }

  when(!isEmpty) {
    // 至少可重命名1个
    when(deqRequest(0)) {
      deq_ptr.io.inc                          := 1.U
      io.renameResultPorts(0).grfWritePort.en := true.B

      val renameAddr = WireDefault(freeQueue(deq_ptr.io.value))
      io.renameResultPorts(0).grfWritePort.addr := renameAddr

      prfPrevMap(renameAddr) := arfToPrfMap(
        io.renameRequestPorts(0).arfWritePort.addr
      )
      arfToPrfMap(io.renameRequestPorts(0).arfWritePort.addr) := renameAddr

      prfStateReg(renameAddr) := State.busy

      // 覆盖1的写重命名
      io.renameResultPorts.zip(io.renameRequestPorts).foreach {
        case (dst, src) =>
          dst.grfReadPorts.zip(src.arfReadPorts).foreach {
            case (dstRead, srcRead) =>
              dstRead.en := srcRead.en
          }
      }

      io.renameRequestPorts(1)
        .arfReadPorts
        .zip(io.renameResultPorts(1).grfReadPorts)
        .foreach {
          case (dstRead, srcRead) =>
            when(srcRead.addr === io.renameRequestPorts(0).arfWritePort.addr) {
              dstRead.addr := renameAddr
            }
        }

      when(deqRequest(1) && !isEmptyByOne) {
        // 重命名2个
        deq_ptr.io.inc                          := 2.U
        io.renameResultPorts(1).grfWritePort.en := true.B

        val renameAddr2 = WireDefault(freeQueue(deq_ptr.io.value + 1.U))
        io.renameResultPorts(1).grfWritePort.addr               := renameAddr2
        prfStateReg(renameAddr2)                                := State.busy
        arfToPrfMap(io.renameRequestPorts(1).arfWritePort.addr) := renameAddr2
        when(
          io.renameRequestPorts(1).arfWritePort.addr =/= io.renameRequestPorts(0).arfWritePort.addr
        ) {
          prfPrevMap(renameAddr2) := arfToPrfMap(
            io.renameRequestPorts(1).arfWritePort.addr
          )
        }.otherwise {
          // write the same reg
          prfPrevMap(renameAddr2) := renameAddr
        }

      }

    }.elsewhen(deqRequest(1)) {
      deq_ptr.io.inc                          := 1.U
      io.renameResultPorts(1).grfWritePort.en := true.B

      val renameAddr = WireDefault(freeQueue(deq_ptr.io.value))
      io.renameResultPorts(1).grfWritePort.addr := renameAddr

      prfPrevMap(renameAddr) := arfToPrfMap(
        io.renameRequestPorts(1).arfWritePort.addr
      )
      arfToPrfMap(io.renameRequestPorts(1).arfWritePort.addr) := renameAddr

      prfStateReg(renameAddr) := State.busy
    }
  }

  /** Rename Retire From WbStage
    *
    * busy --> retire
    */

}
