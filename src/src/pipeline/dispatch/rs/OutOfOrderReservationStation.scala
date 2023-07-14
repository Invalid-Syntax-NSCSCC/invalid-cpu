package pipeline.dispatch.rs

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.ReservationStationBundle
import pipeline.rob.enums.RobDistributeSel
import utils.MultiCounter

class OutOfOrderReservationStation(
  queueLength:          Int,
  enqMaxNum:            Int,
  deqMaxNum:            Int,
  channelNum:           Int,
  channelLength:        Int,
  supportCheckForIssue: Boolean = true)
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

  require(queueLength > enqMaxNum)
  require(queueLength > deqMaxNum)
  require(isPow2(queueLength))

  val ram = RegInit(VecInit(Seq.fill(queueLength)(0.U.asTypeOf(Valid(new ReservationStationBundle)))))

  val enq_ptr = Module(new MultiCounter(queueLength, enqMaxNum))
  val deq_ptr = Module(new MultiCounter(queueLength, deqMaxNum))

  enq_ptr.io.inc   := 0.U
  enq_ptr.io.flush := io.isFlush
  deq_ptr.io.inc   := 0.U
  deq_ptr.io.flush := io.isFlush

  val maybeFull = RegInit(false.B)
  val ptrMatch  = enq_ptr.io.value === deq_ptr.io.value
  val isEmpty   = WireDefault(ptrMatch && !maybeFull)
  val isFull    = WireDefault(ptrMatch && maybeFull)

  val storeNum = WireDefault(
    Mux(
      enq_ptr.io.value === deq_ptr.io.value,
      Mux(isEmpty, 0.U, queueLength.U),
      Mux(
        enq_ptr.io.value > deq_ptr.io.value,
        enq_ptr.io.value - deq_ptr.io.value,
        (queueLength.U -& deq_ptr.io.value) +& enq_ptr.io.value
      )
    )
  )
  val emptyNum = WireDefault(queueLength.U - storeNum)

  val isEmptyBy = WireDefault(VecInit(Seq.range(0, deqMaxNum).map(_.U === storeNum)))
  val isFullBy  = WireDefault(VecInit(Seq.range(0, enqMaxNum).map(_.U === emptyNum)))

  io.enqueuePorts.zipWithIndex.foreach {
    case (enq, idx) =>
      enq.ready := !isFullBy.take(idx + 1).reduce(_ || _)
  }

  val enqEn      = io.enqueuePorts.map(port => (port.ready && port.valid))
  val enqueueNum = enqEn.map(_.asUInt).reduce(_ +& _)

  val deqEn      = io.dequeuePorts.map(port => (port.ready && port.valid))
  val dequeueNum = deqEn.map(_.asUInt).reduce(_ +& _)

  enq_ptr.io.inc := enqueueNum
  deq_ptr.io.inc := dequeueNum

  when(enqueueNum > dequeueNum) {
    maybeFull := true.B
  }.elsewhen(enqueueNum < dequeueNum) {
    maybeFull := false.B
  }

  // dequeue
  // invalidate which are dequeue
  deqEn.zipWithIndex.foreach {
    case (en, idx) =>
      when(en) {
        ram(deq_ptr.io.incResults(idx)).valid := false.B
      }
  }

  // select which can issue
  val ramDownView = WireDefault(VecInit(Seq.range(0, queueLength).map { idx =>
    ram(deq_ptr.io.incResults(idx))
  }))

  val ramDownViewEnableDeq = Wire(Vec(queueLength, Bool()))

  ramDownViewEnableDeq.lazyZip(ramDownView).zipWithIndex.foreach {
    case ((en, elem), idx) =>
      en := elem.valid && elem.bits.robResult.readResults.forall(
        _.sel === RobDistributeSel.realData
      )
      when(
        // elem.bits.regReadPort.preExeInstInfo.needCsr ||
        //   VecInit(ExeInst.Sel.jumpBranch, ExeInst.Sel.loadStore)
        //     .contains(elem.bits.regReadPort.preExeInstInfo.exeSel
        elem.bits.regReadPort.preExeInstInfo.forbidOutOfOrder
      ) {
        if (idx >= enqMaxNum) {
          en := false.B
        } else {
          when(!ramDownViewEnableDeq.take(idx).foldLeft(true.B)(_ && _)) {
            en := false.B
          }
        }
      }
  }

  // select dequeue num of elem to issue
  val selectedDownViewIndices = Wire(Vec(deqMaxNum, UInt(log2Ceil(queueLength).W)))

  selectedDownViewIndices.zipWithIndex.foreach {
    case (selectIndex, idx) =>
      val preSelect = selectedDownViewIndices.take(idx)
      val enables =
        ramDownViewEnableDeq.zipWithIndex.map {
          case (en, en_idx) =>
            en && preSelect.map(_ =/= en_idx.U).foldLeft(true.B)(_ && _)
        }
      selectIndex := PriorityEncoder(enables)
  }

  io.dequeuePorts.lazyZip(selectedDownViewIndices).foreach {
    case (out, selectIndex) =>
      out.valid := ramDownViewEnableDeq(selectIndex)
      out.bits  := ram(deq_ptr.io.incResults(selectIndex)).bits
  }

  // compress
  // deq_ptr - out1 - out2 - enq_ptr
  // for those between out1 and out2, idx += 1
  // for those between deq_ptr and out1 idx += 2 if deqNum >= 2
  val compressFlows = WireDefault(
    0.U.asTypeOf(Vec(deqMaxNum, Vec(queueLength, Valid(new ReservationStationBundle))))
  )
  selectedDownViewIndices.lazyZip(deqEn).zipWithIndex.foreach {
    case ((outIndex, en), offsetTimes) =>
      val src: Vec[Valid[ReservationStationBundle]] =
        if (offsetTimes == 0) {
          ramDownView
        } else {
          compressFlows(offsetTimes - 1)
        }
      when(en) {
        src.zipWithIndex.foreach {
          case (elem, src_idx) =>
            when(src_idx.U === outIndex) {
              // pass
            }.elsewhen(src_idx.U < outIndex) {
              // up one
              if (src_idx != queueLength - 1) {
                compressFlows(offsetTimes)(src_idx + 1) := elem
              }
            }.otherwise {
              // keep position
              compressFlows(offsetTimes)(src_idx) := elem
            }
        }
      }.otherwise {
        src.zipWithIndex.foreach {
          case (elem, src_idx) =>
            // keep position
            compressFlows(offsetTimes)(src_idx) := elem
        }
      }
  }

  // write
  compressFlows(deqMaxNum - 1).zipWithIndex.foreach {
    case (src, idx) =>
      ram(deq_ptr.io.incResults(idx)) := src

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

  // enqueue
  io.enqueuePorts.lazyZip(enqEn).zipWithIndex.foreach {
    case ((in, en), idx) =>
      when(en) {
        ram(enq_ptr.io.incResults(idx)).bits  := in.bits
        ram(enq_ptr.io.incResults(idx)).valid := true.B

        io.writebacks.foreach { wb =>
          in.bits.regReadPort.preExeInstInfo.gprReadPorts
            .lazyZip(in.bits.robResult.readResults)
            .lazyZip(ram(enq_ptr.io.incResults(idx)).bits.robResult.readResults)
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

  when(io.isFlush) {
    ram.foreach(_.valid := false.B)
    maybeFull := false.B
  }

}
