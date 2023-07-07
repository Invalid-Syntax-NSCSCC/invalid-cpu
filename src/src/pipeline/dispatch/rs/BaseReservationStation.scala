package pipeline.dispatch.rs

import chisel3._
import chisel3.util._
import spec.Param
import pipeline.rob.bundles.InstWbNdPort
import pipeline.common.DistributedQueuePlus
import os.read
import pipeline.dispatch.bundles.ReservationStationBundle

class BaseReservationStation(
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
  })

//   val queue = Module(
//     new DistributedQueuePlus(
//       enqMaxNum,
//       deqMaxNum,
//       channelNum,
//       channelLength,
//       elemNdFactory,
//       blankElem
//     )
//   )
}
