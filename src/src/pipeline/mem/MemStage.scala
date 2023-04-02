package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ExeInstNdPort
import spec.ExeInst.Sel
import spec._
import pipeline.ctrl.bundles.PipelineControlNDPort
import pipeline.execution.bundles.MemLoadStoreInfoNdPort
import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import pipeline.mem.bundles.MemLoadStorePort
import pipeline.writeback.bundles.InstInfoNdPort

class MemStage extends Module {
  val io = IO(new Bundle {
    // `ExeStage` -> `MemStage` -> `WbStage`
    val gprWritePassThroughPort = new PassThroughPort(new RfWriteNdPort)
    // `ExeStage` -> `MemStage`
    val memLoadStoreInfoPort = Input(new MemLoadStoreInfoNdPort)
    // `Cu` -> `MemStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)
    // `MemStage` -> Cu
    val stallRequest = Output(Bool())
    // `MemStage` -> ?Ram
    val memLoadStorePort = Flipped(new MemLoadStorePort)

    // (next clock pause)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)
  })

  // Wb debug port connection
  val instInfoReg = Reg(new InstInfoNdPort)
  instInfoReg                    := io.instInfoPassThroughPort.in
  io.instInfoPassThroughPort.out := instInfoReg

  val gprWriteReg = RegInit(RfWriteNdPort.default)
  gprWriteReg := Mux(
    io.pipelineControlPort.stall,
    gprWriteReg,
    io.gprWritePassThroughPort.in
  )
  io.gprWritePassThroughPort.out := gprWriteReg

  io.stallRequest := false.B

  val storeData = WireDefault(io.memLoadStoreInfoPort.data)
  val hint      = WireDefault(io.memLoadStoreInfoPort.data)

  io.memLoadStorePort <> DontCare

  // flush or clear
  when(io.pipelineControlPort.flush || io.pipelineControlPort.clear) {
    gprWriteReg := RfWriteNdPort.default
    InstInfoNdPort.setDefault(instInfoReg)
  }

}