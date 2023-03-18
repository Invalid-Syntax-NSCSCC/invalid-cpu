package pipeline.ctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param
import pipeline.ctrl.bundles.PipelineControlNDPort

class CtrlStage(ctrlControlNum: Integer = Param.ctrlControlNum) extends Module {
    
    val io = IO(new Bundle {
        val stallRequestEx = Input(Bool())
        // dispatch
        val pipelineControlPort = Output(Vec(ctrlControlNum, new PipelineControlNDPort))
    })
}
    