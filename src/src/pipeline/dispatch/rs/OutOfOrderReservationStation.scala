package pipeline.dispatch.rs

import chisel3._
import chisel3.util._
import spec.Param
import pipeline.rob.bundles.InstWbNdPort
import pipeline.common.DistributedQueuePlus
import os.read
import pipeline.dispatch.bundles.ReservationStationBundle
import pipeline.rob.enums.RobDistributeSel
import pipeline.common.MultiQueue
import utils.MultiCounter
import utils.BiPriorityMux

class OutOfOrderReservationStation(
  queueLength:   Int,
  enqMaxNum:     Int,
  deqMaxNum:     Int,
  channelNum:    Int,
  channelLength: Int)
    extends BaseReservationStation(
      queueLength,
      enqMaxNum,
      deqMaxNum,
      channelNum,
      channelLength
    ) {

  require(channelNum > 1)

// Fallback
  io.dequeuePorts.foreach(_ <> DontCare)
  io.enqueuePorts.foreach(_.ready := false.B)
  io.dequeuePorts.foreach(_.valid := false.B)

  // val storeIns = Wire(Vec(channelNum, (DecoupledIO(elemNdFactory))))

  val queues = Seq.fill(channelNum)(
    Module(
      new MultiQueue(
        channelLength,
        1,
        1,
        new ReservationStationBundle,
        ReservationStationBundle.default,
        writeFirst = false
      )
    )
  )
  queues.foreach(_.io.isFlush := io.isFlush)

  val storeIns  = VecInit(queues.map(_.io.enqueuePorts(0)))
  val storeOuts = VecInit(queues.map(_.io.dequeuePorts(0)))

  storeIns.foreach { in =>
    in.valid := false.B
    in.bits  := DontCare
  }

  storeOuts.foreach(_.ready := false.B)
  // if (channelNum == 1) {
  //   io.enqueuePorts(0) <> storeIns(0)
  //   io.dequeuePorts(0) <> storeOuts(0)
  // } else {
  val enq_ptr = Module(new MultiCounter(channelNum, enqMaxNum))

  enq_ptr.io.flush := io.isFlush

  enq_ptr.io.inc := io.enqueuePorts.zipWithIndex.map {
    case (enqPort, idx) =>
      // connect
      enqPort <> storeIns(enq_ptr.io.incResults(idx))
      // return
      enqPort.valid && storeIns(enq_ptr.io.incResults(idx)).ready
  }.map(_.asUInt).reduce(_ +& _)

  // TODO: deq port

  // enable to out
  val deqValids = storeOuts.map { port =>
    port.valid && port.bits.robResult.readResults.forall(
      _.sel === RobDistributeSel.realData
    )
  }

  val selectIndices = Wire(Vec(deqMaxNum, Valid(UInt(log2Ceil(deqMaxNum).W))))
  selectIndices.foreach { selectIndex =>
    selectIndex.valid := false.B
    selectIndex.bits  := DontCare
  }
  selectIndices.zipWithIndex.foreach {
    case (selectIndex, dst_idx) =>
      deqValids.zipWithIndex.foreach {
        case (deqValid, src_idx) =>
          when(deqValid && selectIndices.take(dst_idx).map(_.bits =/= src_idx.U).foldLeft(true.B)(_ && _)) {
            selectIndex.valid := true.B
            selectIndex.bits  := src_idx.U
          }
      }
  }

  io.dequeuePorts.zip(selectIndices).foreach {
    case (out, selectIndex) =>
      out.valid                   := selectIndex.valid
      out.bits                    := storeOuts(selectIndex.bits)
      storeOuts(selectIndex.bits) := out.ready
  }

  // }

}
