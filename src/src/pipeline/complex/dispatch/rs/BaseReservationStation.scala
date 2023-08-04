package pipeline.complex.dispatch.rs

import chisel3._
import chisel3.util._
import pipeline.complex.dispatch.bundles.ReservationStationBundle
import pipeline.complex.rob.bundles.InstWbNdPort
import pipeline.complex.pmu.bundles.PmuDispatchBundle
import spec.Param

abstract class BaseReservationStation(
  queueLength:   Int,
  enqMaxNum:     Int,
  deqMaxNum:     Int,
  channelNum:    Int,
  channelLength: Int)
    extends Module {
  val io = IO(new Bundle {
    val isFlush      = Input(Bool())
    val enqueuePorts = Vec(enqMaxNum, Flipped(Decoupled(new ReservationStationBundle)))
    val dequeuePorts = Vec(deqMaxNum, Decoupled(new ReservationStationBundle))
    val writebacks   = Input(Vec(Param.pipelineNum, new InstWbNdPort))

    val pmu_dispatchInfo = if (Param.usePmu) Some(Output(new PmuDispatchBundle)) else None
  })
  require(queueLength == channelNum * channelLength)
}
