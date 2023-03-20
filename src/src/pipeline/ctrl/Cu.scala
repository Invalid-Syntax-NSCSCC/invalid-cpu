package pipeline.ctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param
import pipeline.ctrl.bundles.PipelineControlNDPort

class Cu(ctrlControlNum: Int = Param.ctrlControlNum) extends Module {
  val io = IO(new Bundle {
    // `ExeStage` -> `CtrlStage`
    val exeStallRequest = Input(Bool())
    // `CtrlStage` -> `IssueStage`, `RegReadStage`, `ExeStage`
    val pipelineControlPorts = Output(Vec(ctrlControlNum, new PipelineControlNDPort))
  })

  io.pipelineControlPorts := DontCare
  io.pipelineControlPorts.take(3).foreach(_.stall := io.exeStallRequest)
}
