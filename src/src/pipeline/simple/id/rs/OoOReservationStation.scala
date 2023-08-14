package pipeline.simple.id.rs

import chisel3._
import chisel3.util._
import pipeline.simple.bundles.{RSBundle, RegWakeUpNdPort}
import spec._
import utils._

class ReservationStation[T <: RSBundle](
  queueLength: Int,
  rsFactory:   => T,
  rsBlankElem: => T,
  hasInOrder:  Boolean = true)
    extends Module {

  val wakeUpNum = Param.pipelineNum + 1

  val io = IO(new Bundle {
    val isFlush     = Input(Bool())
    val enqueuePort = Flipped(Decoupled(rsFactory))
    val dequeuePort = Decoupled(rsFactory)
    val writebacks  = Input(Vec(wakeUpNum, new RegWakeUpNdPort))

    // val pmu_dispatchInfo = if (Param.usePmu) Some(Output(new PmuDispatchBundle)) else None
  })

  // Fallback
  io.dequeuePort.valid := false.B
  io.dequeuePort.bits  := DontCare
  io.enqueuePort.ready := false.B

  require(queueLength > 1)
  require(queueLength > 1)
  require(isPow2(queueLength))

  val ramValids = RegInit(VecInit(Seq.fill(queueLength)(false.B)))
  // val ram        = RegInit(VecInit(Seq.fill(queueLength)((RSBundle.default))))
  val ram = Reg(Vec(queueLength, rsFactory))
  ram.foreach(_ := DontCare)
  val ramFillRes = WireDefault(ram)
  ram
    .lazyZip(ramFillRes)
    .foreach {
      case (elem, res) =>
        res := elem
        res.regReadResults.zip(elem.regReadResults).foreach {
          case (dst, src) =>
            val mux = Module(new MultiMux1(wakeUpNum, UInt(spec.Width.Reg.data), zeroWord))
            mux.io.inputs.zip(io.writebacks).foreach {
              case (input, wb) =>
                input.valid := wb.en && src.bits(Param.Width.Rob._id - 1, 0) === wb.robId
                input.bits  := wb.data
            }
            when(!src.valid && mux.io.output.valid) {
              dst.valid := true.B
              dst.bits  := mux.io.output.bits
            }
        }
    }

  val enqEn       = io.enqueuePort.valid && io.enqueuePort.ready
  val deqEn       = io.dequeuePort.valid && io.dequeuePort.ready
  val ptr         = RegInit(0.U(log2Ceil(queueLength + 1).W))
  val enqStartPtr = ptr -& deqEn.asUInt
  val newPtr      = ptr +& enqEn.asUInt -& deqEn.asUInt

  ptr := newPtr
  ramValids.zipWithIndex.foreach {
    case (valid, idx) =>
      valid := idx.U < newPtr
  }

  val in = io.enqueuePort
  in.ready := ptr < queueLength.U

  val enableToDequeues = WireDefault(VecInit(Seq.fill(queueLength)(false.B)))
  enableToDequeues.lazyZip(ramFillRes).lazyZip(ramValids).zipWithIndex.foreach {
    case ((en, elem, elemValid), idx) =>
      en := elemValid && elem.regReadResults
        .forall(
          _.valid === true.B
        )
      if (idx != 0 && hasInOrder) {
        when(elem.decodePort.decode.info.forbidOutOfOrder) {
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

  io.dequeuePort.valid := enableToDequeues.reduceTree(_ || _)

  // // assert deq num = 1
  val selectedIndex = PriorityEncoder(enableToDequeues)

  io.dequeuePort.bits := ramFillRes(selectedIndex)

  ram.zip(ramFillRes).zipWithIndex.foreach {
    case ((dst, src), idx) =>
      dst := src
      if (idx != queueLength - 1) {
        when(deqEn && idx.U >= selectedIndex) {
          dst := ramFillRes(idx + 1)
        }
      }
  }

  when(enqEn) {
    ram(enqStartPtr) := in.bits
  }

  // if (Param.usePmu) {
  //   val pmu     = io.pmu_dispatchInfo.get
  //   val isFull  = ptr === queueLength.U
  //   val isEmpty = ptr === 0.U
  //   pmu.enqueue           := io.enqueuePorts.head.valid && io.enqueuePorts.head.ready && !io.isFlush
  //   pmu.isFull            := isFull && !io.isFlush
  //   pmu.bubbleFromBackend := io.dequeuePorts.head.valid && !io.dequeuePorts.head.ready && !io.isFlush
  //   pmu.bubbleFromRSEmpty := isEmpty && !io.isFlush
  //   pmu.bubbleFromDataDependence := !pmu.bubbleFromRSEmpty &&
  //     !pmu.bubbleFromBackend &&
  //     !io.dequeuePorts.head.valid &&
  //     !isEmpty &&
  //     !io.isFlush
  // }

  when(io.isFlush) {
    ramValids.foreach(_ := false.B)
    ptr := 0.U
  }

}
