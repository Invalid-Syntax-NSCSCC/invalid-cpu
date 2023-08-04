package pipeline.complex.dispatch.rs

import chisel3._
import chisel3.util._
import pipeline.common.enums.RobDistributeSel
import pipeline.complex.dispatch.bundles.ReservationStationBundle
import spec._
import utils.{MultiCounter, MultiMux1}

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

  val ramDownView = Wire(Vec(queueLength, Valid(new ReservationStationBundle)))
  ramDownView.zipWithIndex.foreach {
    case (dst, idx) =>
      val src = ram(deq_ptr.io.incResults(idx))
      dst := src
      if (Param.isWritebackPassThroughWakeUp) {
        io.writebacks.foreach { wb =>
          dst.bits.robResult.readResults.zip(src.bits.robResult.readResults).foreach {
            case (dstRead, srcRead) =>
              when(srcRead.sel === RobDistributeSel.robId && wb.en && wb.robId === srcRead.result) {
                dstRead.sel    := RobDistributeSel.realData
                dstRead.result := wb.data
              }
          }
        }
      }
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

        src.bits.regReadPort.preExeInstInfo.gprReadPorts
          .lazyZip(src.bits.robResult.readResults)
          .lazyZip(ram(deq_ptr.io.incResults(idx)).bits.robResult.readResults)
          .foreach {
            case (readPort, robReadResult, dst) =>
              if (Param.isOptimizedByMultiMux) {
                val mux = Module(new MultiMux1(Param.pipelineNum, UInt(Width.Reg.data), zeroWord))
                mux.io.inputs.zip(io.writebacks).foreach {
                  case (input, wb) =>
                    input.valid := wb.en && wb.robId === robReadResult.result
                    input.bits  := wb.data
                }
                when(mux.io.output.valid && readPort.en && robReadResult.sel === RobDistributeSel.robId) {
                  dst.sel    := RobDistributeSel.realData
                  dst.result := mux.io.output.bits
                }
              } else {
                io.writebacks.foreach { wb =>
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
  }

  // enqueue
  val in = io.enqueuePorts.head
  when(enqEn) {
    ram(enq_ptr.io.value).bits  := in.bits
    ram(enq_ptr.io.value).valid := true.B

    in.bits.regReadPort.preExeInstInfo.gprReadPorts
      .lazyZip(in.bits.robResult.readResults)
      .lazyZip(ram(enq_ptr.io.value).bits.robResult.readResults)
      .foreach {
        case (readPort, robReadResult, dst) =>
          io.writebacks.foreach { wb =>
            if (Param.isOptimizedByMultiMux) {
              val mux = Module(new MultiMux1(Param.pipelineNum, UInt(Width.Reg.data), zeroWord))
              mux.io.inputs.zip(io.writebacks).foreach {
                case (input, wb) =>
                  input.valid := wb.en && wb.robId === robReadResult.result
                  input.bits  := wb.data
              }
              when(mux.io.output.valid && readPort.en && robReadResult.sel === RobDistributeSel.robId) {
                dst.sel    := RobDistributeSel.realData
                dst.result := mux.io.output.bits
              }
            } else {
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

  if (Param.usePmu) {
    val pmu = io.pmu_dispatchInfo.get
    pmu.enqueue           := io.enqueuePorts.head.valid && io.enqueuePorts.head.ready && !io.isFlush
    pmu.isFull            := isFull && !io.isFlush
    pmu.bubbleFromBackend := io.dequeuePorts.head.valid && !io.dequeuePorts.head.ready && !io.isFlush
    pmu.bubbleFromRSEmpty := isEmpty && !io.isFlush
    pmu.bubbleFromDataDependence := !pmu.bubbleFromRSEmpty &&
      !pmu.bubbleFromBackend &&
      !io.dequeuePorts.head.valid &&
      !isEmpty &&
      !io.isFlush
  }

  when(io.isFlush) {
    ram.foreach(_.valid := false.B)
    maybeFull := false.B
  }

}