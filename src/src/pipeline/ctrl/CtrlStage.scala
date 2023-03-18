package pipeline.ctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class CtrlStage extends Module {
    
    val io = IO(new Bundle {
        val stallRequestEx = Input(Bool())
    })
}
    