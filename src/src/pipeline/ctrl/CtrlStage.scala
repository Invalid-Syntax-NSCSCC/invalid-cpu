package pipeline.ctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param
import pipeline.ctrl.bundles.PipelineControlNDPort

class CtrlStage(ctrlControlNum: Integer = Param.ctrlControlNum) extends Module {
    
    val io = IO(new Bundle {
        // `ExeStage` -> `CtrlStage`
        val exeStallRequest = Input(Bool())
        // `CtrlStage` -> `IssueStage`, `RegReadStage`, `ExeStage`
        val pipelineControlPort = Output(Vec(ctrlControlNum, new PipelineControlNDPort))
    })

    io.pipelineControlPort := DontCare
    Seq.range(0,3).foreach(io.pipelineControlPort(_).stall := io.exeStallRequest)

}
    