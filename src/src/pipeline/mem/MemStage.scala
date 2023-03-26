package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ExeInstNdPort
import spec.ExeInst.Sel
import spec._
import pipeline.ctrl.bundles.PipelineControlNDPort
import pipeline.execution.bundles.MemLoadStoreNdPort
import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class MemStage extends Module {
  val io = IO(new Bundle {
    // `ExeStage` -> `MemStage` -> `WbStage`
    val gprWritePassThroughPort = new PassThroughPort(new RfWriteNdPort)
    // `ExeStage` -> `MemStage`
    val memLoadStorePort = Input(new MemLoadStoreNdPort)
    // `Cu` -> `MemStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)
    // `MemStage` -> Cu
    val stallRequest = Output(Bool())
  })

  val gprWriteReg = RegInit(RfWriteNdPort.default)
  gprWriteReg := Mux(
    io.pipelineControlPort.stall,
    gprWriteReg,
    io.gprWritePassThroughPort.in
  )
  io.gprWritePassThroughPort.out := gprWriteReg

  io.stallRequest := false.B
}
