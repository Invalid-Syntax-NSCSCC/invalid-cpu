package pipeline.dispatch.rs

import chisel3._
import chisel3.util._
import pipeline.common.DistributedQueuePlus
import pipeline.dispatch.bundles.ReservationStationBundle
import pipeline.rob.enums.RobDistributeSel
import spec.Param.isWritebackPassThroughWakeUp
import chisel3.experimental.Param
import spec._
import utils._

class OoOReservationStation(
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
  require(supportCheckForIssue == true)

  // Fallback
  io.dequeuePorts.foreach(_ <> DontCare)
  io.enqueuePorts.foreach(_.ready := false.B)
  io.dequeuePorts.foreach(_.valid := false.B)

  require(queueLength > 1)
  require(queueLength > 1)
  require(isPow2(queueLength))

  // TODO: delete
  require(deqMaxNum == 1)

  val ramValids  = RegInit(VecInit(Seq.fill(queueLength)(false.B)))
  val ram        = RegInit(VecInit(Seq.fill(queueLength)((ReservationStationBundle.default))))
  val ramFillRes = WireDefault(ram)
  ram
    .lazyZip(ramFillRes)
    .foreach {
      case (elem, set) =>
        set := elem
        elem.robResult.readResults.zip(set.robResult.readResults).foreach {
          case (readResult, setReadResult) =>
            if (spec.Param.isOptimizedByMultiMux) {
              val mux = Module(new MultiMux1(spec.Param.pipelineNum, UInt(spec.Width.Reg.data), zeroWord))
              mux.io.inputs.zip(io.writebacks).foreach {
                case (input, wb) =>
                  input.valid := wb.en && readResult.result === wb.robId
                  input.bits  := wb.data
              }
              when(readResult.sel === RobDistributeSel.robId && mux.io.output.valid) {
                setReadResult.sel    := RobDistributeSel.realData
                setReadResult.result := mux.io.output.bits
              }
            } else {

              io.writebacks.foreach { wb =>
                when(readResult.sel === RobDistributeSel.robId && wb.en && readResult.result === wb.robId) {
                  setReadResult.sel    := RobDistributeSel.realData
                  setReadResult.result := wb.data
                }
              }
            }
        }
    }

  val enqEns      = io.enqueuePorts.map { port => port.valid && port.ready }
  val deqEns      = io.dequeuePorts.map { port => port.valid && port.ready }
  val ptr         = RegInit(0.U(log2Ceil(queueLength + 1).W))
  val enqStartPtr = WireDefault(ptr -& deqEns.map(_.asUInt).reduce(_ +& _))
  val newPtr      = WireDefault(ptr +& enqEns.map(_.asUInt).reduce(_ +& _) -& deqEns.map(_.asUInt).reduce(_ +& _))
  ptr := newPtr
  ramValids.zipWithIndex.foreach {
    case (valid, idx) =>
      valid := idx.U < newPtr
  }

  val enqs = Wire(Vec(enqMaxNum, Flipped(Decoupled(new ReservationStationBundle))))
  enqs.zip(io.enqueuePorts).foreach {
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

  enqs.zipWithIndex.foreach {
    case (enq, idx) =>
      enq.ready := ptr < (queueLength - idx).U
  }

  val enableToDequeues   = WireDefault(VecInit(Seq.fill(queueLength)(false.B)))
  val enableToDequeueNum = enableToDequeues.map(_.asUInt).reduce(_ +& _)
  enableToDequeues.lazyZip(ramFillRes).lazyZip(ramValids).zipWithIndex.foreach {
    case ((en, elem, elemValid), idx) =>
      en := elemValid && elem.robResult.readResults.map(_.sel === RobDistributeSel.realData).reduce(_ && _)
      if (idx != 0) {
        when(elem.regReadPort.preExeInstInfo.forbidOutOfOrder) {
          en := false.B
        }
      }
    // 备选 ipc++ 但延迟++
    // when(
    //   elem.regReadPort.preExeInstInfo.forbidOutOfOrder &&
    //     ramFillRes.take(idx).map(_.regReadPort.preExeInstInfo.forbidOutOfOrder).foldLeft(false.B)(_ || _)
    // ) {
    //   en := false.B
    // }
  }

  io.dequeuePorts.zipWithIndex.foreach {
    case (deq, idx) =>
      deq.valid := idx.U < enableToDequeueNum
  }

  // assert deq num = 1
  val selectedIndices = Seq(PriorityEncoder(enableToDequeues))

  io.dequeuePorts.zip(selectedIndices).foreach {
    case (deq, selectedIndex) =>
      deq.bits := ramFillRes(selectedIndex)
  }

  // compress & update
  val ramOutFlags = WireDefault(VecInit(Seq.fill(queueLength)(false.B)))
  selectedIndices.zipWithIndex.foreach {
    case (selectIndex, idx) =>
      ramOutFlags(selectIndex) := deqEns(idx)
  }

  val shiftNums = Wire(Vec(queueLength, UInt(log2Ceil(deqMaxNum + 1).W)))
  shiftNums.zipWithIndex.foreach {
    case (dst, idx) =>
      dst := ramOutFlags.take(idx).map(_.asUInt).foldLeft(0.U)(_ +& _)
  }

  ramFillRes.zip(shiftNums).zipWithIndex.foreach {
    case ((nextElem, shiftNum), idx) =>
      ram(idx.U -& shiftNum) := nextElem
  }

  enqs.zip(enqEns).zipWithIndex.foreach {
    case ((enq, en), idx) =>
      when(en) { // can delete ?
        ram(enqStartPtr + idx.U) := enq.bits
      }
  }

  if (Param.usePmu) {
    val pmu     = io.pmu_dispatchInfo.get
    val isFull  = ptr === queueLength.U
    val isEmpty = ptr === 0.U
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
    ramValids.foreach(_ := false.B)
    ptr := 0.U
  }

}
