package pipeline.dispatch.rs

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.ReservationStationBundle
import pipeline.rob.bundles.InstWbNdPort
import spec.Param
import pmu.bundles.PmuDispatchBundle

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
}
