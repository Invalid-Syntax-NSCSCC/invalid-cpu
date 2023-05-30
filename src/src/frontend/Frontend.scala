package frontend

import axi.AxiMaster
import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util._
import control.bundles.PipelineControlNdPort
import pipeline.dispatch.bundles.InstInfoBundle
import spec._
import frontend.bundles.ICacheAccessPort
import frontend.InstFetchStage

class Frontend extends Module {
  val io = IO(new Bundle {
    // <-> ICache
    val iCacheAccessPort = Flipped(new ICacheAccessPort)

    // <-> Frontend <-> Instrution queue
    val pc              = Input(UInt(Width.Reg.data))
    val isPcNext        = Output(Bool())
    val isFlush         = Input(Bool())
    val instEnqueuePort = Decoupled(new InstInfoBundle)

    // TODO mul FetchNum
    //     // val instEnqueuePorts = Vec(Param.Count.frontend.instFetchNum, Flipped(Decoupled(new InstInfoBundle)))
  })
  val instFetchStage = Module(new InstFetchStage)
  instFetchStage.io.iCacheAccessPort <> io.iCacheAccessPort
  instFetchStage.io.instEnqueuePort  <> io.instEnqueuePort
  instFetchStage.io.isFlush          := io.isFlush
  instFetchStage.io.pc               := io.pc
  io.isPcNext                        := instFetchStage.io.isPcNext
}
