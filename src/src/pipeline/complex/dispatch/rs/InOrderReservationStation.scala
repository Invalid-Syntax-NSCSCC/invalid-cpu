package pipeline.complex.dispatch.rs

import chisel3._
import common.DistributedQueuePlus
import pipeline.common.enums.RobDistributeSel
import pipeline.complex.dispatch.bundles.ReservationStationBundle
import spec.Param.isWritebackPassThroughWakeUp
import spec._
import utils.MultiMux1

class InOrderReservationStation(
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
      in.bits.regReadPort.preExeInstInfo.gprReadPorts
        .lazyZip(in.bits.robResult.readResults)
        .lazyZip(enq.bits.robResult.readResults)
        .foreach {
          case (readPort, robReadResult, dst) =>
            io.writebacks.foreach { wb =>
              if (spec.Param.isOptimizedByMultiMux) {
                val mux = Module(new MultiMux1(spec.Param.pipelineNum, UInt(spec.Width.Reg.data), zeroWord))
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

  queue.io.dequeuePorts.zip(io.dequeuePorts).foreach {
    case (deq, out) =>
      if (supportCheckForIssue) {
        val outBits = WireDefault(deq.bits)
        if (isWritebackPassThroughWakeUp) {
          outBits.robResult.readResults.zip(deq.bits.robResult.readResults).foreach {
            case (dst, src) =>
              when(src.sel === RobDistributeSel.robId) {
                if (spec.Param.isOptimizedByMultiMux) {
                  val mux = Module(new MultiMux1(spec.Param.pipelineNum, UInt(spec.Width.Reg.data), zeroWord))
                  mux.io.inputs.zip(io.writebacks).foreach {
                    case (input, wb) =>
                      input.valid := wb.en && src.result === wb.robId
                      input.bits  := wb.data
                  }
                  when(mux.io.output.valid) {
                    dst.sel    := RobDistributeSel.realData
                    dst.result := mux.io.output.bits
                  }
                } else {
                  io.writebacks.foreach { wb =>
                    when(wb.en && src.result === wb.robId) {
                      dst.sel    := RobDistributeSel.realData
                      dst.result := wb.data
                    }
                  }
                }
              }
          }
        }
        val supportIssue = outBits.robResult.readResults.forall(
          _.sel === RobDistributeSel.realData
        )
        out.valid := deq.valid && supportIssue
        out.bits  := outBits
        deq.ready := out.ready && supportIssue
      } else {
        out <> deq
      }

  }

  queue.io.isFlush := io.isFlush
  queue.io.setPorts.zip(queue.io.elems).foreach {
    case (set, elem) =>
      set.valid := false.B
      set.bits  := elem
  }

  // commit fill reservation station
  queue.io.elems
    .lazyZip(queue.io.setPorts)
    .foreach {
      case (elem, set) =>
        elem.robResult.readResults.zip(set.bits.robResult.readResults).foreach {
          case (readResult, setReadResult) =>
            if (spec.Param.isOptimizedByMultiMux) {
              val mux = Module(new MultiMux1(spec.Param.pipelineNum, UInt(spec.Width.Reg.data), zeroWord))
              mux.io.inputs.zip(io.writebacks).foreach {
                case (input, wb) =>
                  input.valid := wb.en && readResult.result === wb.robId
                  input.bits  := wb.data
              }
              when(readResult.sel === RobDistributeSel.robId && mux.io.output.valid) {
                set.valid            := true.B
                setReadResult.sel    := RobDistributeSel.realData
                setReadResult.result := mux.io.output.bits
              }
            } else {

              io.writebacks.foreach { wb =>
                when(readResult.sel === RobDistributeSel.robId && wb.en && readResult.result === wb.robId) {
                  set.valid            := true.B
                  setReadResult.sel    := RobDistributeSel.realData
                  setReadResult.result := wb.data
                }
              }
            }
        }
    }

  if (Param.usePmu) {
    val pmu     = io.pmu_dispatchInfo.get
    val isFull  = !queue.io.enqueuePorts.head.ready
    val isEmpty = !queue.io.dequeuePorts.head.valid
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
}
