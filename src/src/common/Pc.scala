package common

import chisel3._
import chisel3.util._
import common.bundles._
import spec._
import pipeline.execution.bundles.JumpBranchInfoNdPort
import control.bundles.PipelineControlNDPort

class Pc extends Module {
  val io = IO(new Bundle {
    val pc     = Output(UInt(Width.Reg.data))
    val isNext = Input(Bool())
    // `ExeStage` -> `Pc` (no delay)
    val branchSetPort = Input(new PcSetPort)
    // 异常处理
    val flushNewPc = Input(new PcSetPort)
  })

  val pcReg = RegInit(spec.Pc.init)
  io.pc := pcReg

  pcReg := pcReg
  when(io.flushNewPc.en) {
    pcReg := io.flushNewPc.pcAddr
  }.elsewhen(io.branchSetPort.en) {
    pcReg := io.branchSetPort.pcAddr
  }.elsewhen(io.isNext) {
    pcReg := pcReg + 4.U
  }
}
