package pipeline.ctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param
import pipeline.ctrl.bundles.PipelineControlNDPort
import spec.PipelineStageIndex
import pipeline.writeback.bundles.InstInfoNdPort

// TODO: Add stall to frontend ?
// TODO: Add flush to stages
// TODO: Add deal exceptions
class Cu(ctrlControlNum: Int = Param.ctrlControlNum) extends Module {
  val io = IO(new Bundle {
    // `WbStage` -> `Cu`
    val instInfoPort = Input(new InstInfoNdPort)
    // `ExeStage` -> `Cu`
    val exeStallRequest = Input(Bool())
    // `MemStage` -> `Cu`
    val memStallRequest = Input(Bool())
    // `Cu` -> `IssueStage`, `RegReadStage`, `ExeStage`, `MemStage`
    val pipelineControlPorts = Output(Vec(ctrlControlNum, new PipelineControlNDPort))
  })

  io.pipelineControlPorts := DontCare
  // `ExeStage` --stall--> `IssueStage`, `RegReadStage` (DONT STALL ITSELF)
  Seq(PipelineStageIndex.issueStage, PipelineStageIndex.regReadStage)
    .map(io.pipelineControlPorts(_))
    .foreach(_.stall := io.exeStallRequest)
  // `MemStage` --stall--> `IssueStage`, `RegReadStage`, `ExeStage`  (DONT STALL ITSELF)
  Seq(PipelineStageIndex.issueStage, PipelineStageIndex.regReadStage, PipelineStageIndex.exeStage)
    .map(io.pipelineControlPorts(_))
    .foreach(_.stall := io.memStallRequest)
}
