package pipeline.dispatch.rs

import chisel3._
import chisel3.util._
import spec.Param
import pipeline.rob.bundles.InstWbNdPort
import pipeline.common.DistributedQueuePlus
import os.read
import pipeline.dispatch.bundles.ReservationStationBundle
import pipeline.rob.enums.RobDistributeSel

class InOrderReservationStation(
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

  val queue = Module(
    new DistributedQueuePlus(
      enqMaxNum,
      deqMaxNum,
      channelNum,
      channelLength,
      new ReservationStationBundle,
      ReservationStationBundle.default
    )
  )

  queue.io.enqueuePorts.zip(io.enqueuePorts).foreach {
    case (enq, in) =>
      in.ready  := enq.ready
      enq.valid := in.valid
      enq.bits  := in.bits
      io.writebacks.foreach { wb =>
        in.bits.regReadPort.preExeInstInfo.gprReadPorts
          .lazyZip(in.bits.robResult.readResults)
          .lazyZip(enq.bits.robResult.readResults)
          .foreach {
            case (readPort, robReadResult, dst) =>
              when(
                wb.en && readPort.en &&
                  robReadResult.sel === RobDistributeSel.robId &&
                  wb.robId === robReadResult.result
              ) {
                dst.sel    := RobDistributeSel.realData
                dst.result := wb.data
              }
          }
      }
  }

  queue.io.dequeuePorts.zip(io.dequeuePorts).foreach {
    case (deq, out) =>
      out.valid := deq.valid && deq.bits.robResult.readResults.forall(
        _.sel === RobDistributeSel.realData
      )
      out.bits  := deq.bits
      deq.ready := out.ready
  }

  queue.io.isFlush := io.isFlush
  queue.io.setPorts.foreach { set =>
    set.valid := false.B
    set.bits  := DontCare
  }

  // commit fill reservation station
  queue.io.elems
    .lazyZip(queue.io.setPorts)
    .foreach {
      case (elem, set) =>
        io.writebacks.foreach { wb =>
          elem.robResult.readResults.zip(set.bits.robResult.readResults).foreach {
            case (readResult, setReadResult) =>
              when(readResult.sel === RobDistributeSel.robId && wb.en && readResult.result === wb.robId) {
                set.valid            := true.B
                setReadResult.sel    := RobDistributeSel.realData
                setReadResult.result := wb.data
              }
          }
        }
    }

}
