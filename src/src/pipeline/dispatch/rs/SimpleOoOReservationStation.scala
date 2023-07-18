package pipeline.dispatch.rs

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.ReservationStationBundle
import pipeline.rob.enums.RobDistributeSel
import utils.MultiCounter
import spec.Param

class SimpleOoOReservationStation(
  queueLength:          Int,
  supportCheckForIssue: Boolean = true)
    extends BaseReservationStation(
      queueLength,
      1,
      1,
      1,
      queueLength
    ) {

  require(supportCheckForIssue == true)

// Fallback
  io.dequeuePorts.foreach(_ <> DontCare)
  io.enqueuePorts.foreach(_.ready := false.B)
  io.dequeuePorts.foreach(_.valid := false.B)

  require(queueLength > 1)
  require(queueLength > 1)
  require(isPow2(queueLength))

  val ram = RegInit(VecInit(Seq.fill(queueLength)(0.U.asTypeOf(Valid(new ReservationStationBundle)))))

  val enq_ptr = Module(new MultiCounter(queueLength, 1))
  val deq_ptr = Module(new MultiCounter(queueLength, 1))

  enq_ptr.io.inc   := 0.U
  enq_ptr.io.flush := io.isFlush
  deq_ptr.io.inc   := 0.U
  deq_ptr.io.flush := io.isFlush

  val maybeFull = RegInit(false.B)
  val ptrMatch  = enq_ptr.io.value === deq_ptr.io.value
  val isEmpty   = WireDefault(ptrMatch && !maybeFull)
  val isFull    = WireDefault(ptrMatch && maybeFull)

  io.enqueuePorts.head.ready := !isFull

  val enqEn      = io.enqueuePorts.head.valid && io.enqueuePorts.head.ready
  val enqueueNum = enqEn.asUInt

  val deqEn      = io.dequeuePorts.head.valid && io.dequeuePorts.head.ready
  val dequeueNum = deqEn.asUInt

  enq_ptr.io.inc := enqueueNum
  deq_ptr.io.inc := dequeueNum

  when(enqueueNum > dequeueNum) {
    maybeFull := true.B
  }.elsewhen(enqueueNum < dequeueNum) {
    maybeFull := false.B
  }

  // dequeue
  // invalidate which are dequeue
  when(deqEn) {
    ram(deq_ptr.io.value).valid := false.B
  }

  // select which can issue
  // val ramDownView = WireDefault(VecInit(Seq.range(0, queueLength).map { idx =>
  //   ram(deq_ptr.io.incResults(idx))
  // // if (!Param.isWritebackPassThroughWakeUp)
  // }))

  val ramDownView = Wire(Vec(queueLength, Valid(new ReservationStationBundle)))
  ramDownView.zipWithIndex.foreach {
    case (dst, idx) =>
      val ramElem = ram(deq_ptr.io.incResults(idx))
      dst := ramElem
  }

  val ramDownViewEnableDeq = Wire(Vec(queueLength, Bool()))

  ramDownViewEnableDeq.lazyZip(ramDownView).zipWithIndex.foreach {
    case ((en, elem), idx) =>
      en := elem.valid && elem.bits.robResult.readResults.forall(
        _.sel === RobDistributeSel.realData
      )
      if (idx != 0) {
        when(elem.bits.regReadPort.preExeInstInfo.forbidOutOfOrder) {
          en := false.B
        }
      }
  }

  // // select dequeue num of elem to issue
  val selectedDownViewIndex = PriorityEncoder(ramDownViewEnableDeq)

  io.dequeuePorts.head.valid := ramDownViewEnableDeq(selectedDownViewIndex)
  io.dequeuePorts.head.bits  := ramDownView(selectedDownViewIndex).bits

  // compress
  // deq_ptr - out - enq_ptr
  // for those between deq_ptr and out idx += 1
  val compressResult = Wire(Vec(queueLength, Valid(new ReservationStationBundle)))

  when(deqEn) {
    compressResult.zip(ramDownView).foreach {
      case (dst, src) =>
        dst.valid := false.B
        dst.bits  := DontCare
    }
    ramDownView.zipWithIndex.foreach {
      case (elem, src_idx) =>
        when(src_idx.U === selectedDownViewIndex) {
          // pass
        }.elsewhen(src_idx.U < selectedDownViewIndex) {
          // up one
          if (src_idx != queueLength - 1) {
            compressResult(src_idx + 1) := elem
          }
        }.otherwise {
          compressResult(src_idx) := elem
        }
    }
  }.otherwise {
    compressResult.zip(ramDownView).foreach {
      case (dst, src) =>
        dst := src
    }
  }

  // // write
  compressResult.zipWithIndex.foreach {
    case (src, idx) =>
      ram(deq_ptr.io.incResults(idx)) := src

      if (!Param.isWritebackPassThroughWakeUp) {
        io.writebacks.foreach { wb =>
          src.bits.regReadPort.preExeInstInfo.gprReadPorts
            .lazyZip(src.bits.robResult.readResults)
            .lazyZip(ram(deq_ptr.io.incResults(idx)).bits.robResult.readResults)
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
  }

  // enqueue
  val in = io.enqueuePorts.head
  when(enqEn) {
    ram(enq_ptr.io.value).bits  := in.bits
    ram(enq_ptr.io.value).valid := true.B

    io.writebacks.foreach { wb =>
      in.bits.regReadPort.preExeInstInfo.gprReadPorts
        .lazyZip(in.bits.robResult.readResults)
        .lazyZip(ram(enq_ptr.io.value).bits.robResult.readResults)
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

  when(io.isFlush) {
    ram.foreach(_.valid := false.B)
    maybeFull := false.B
  }

}
