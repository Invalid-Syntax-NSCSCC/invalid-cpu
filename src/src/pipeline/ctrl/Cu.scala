package pipeline.ctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param
import pipeline.ctrl.bundles.PipelineControlNDPort

class Cu(ctrlControlNum: Int = Param.ctrlControlNum) extends Module {
  val io = IO(new Bundle {
    // `ExeStage` -> `Cu`
    val exeStallRequest = Input(Bool())
    // `Cu` -> `IssueStage`, `RegReadStage`, `ExeStage`
    val pipelineControlPorts = Output(Vec(ctrlControlNum, new PipelineControlNDPort))
  })

  io.pipelineControlPorts := DontCare
  // `ExeStage` --stall--> `IssueStage`, `RegReadStage` (DONT STALL ITSELF)
  io.pipelineControlPorts.take(2).foreach(_.stall := io.exeStallRequest)
}
