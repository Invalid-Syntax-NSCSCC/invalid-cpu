package common

import chisel3._
import chisel3.util._
import common.bundles._
import spec._
import pipeline.execution.bundles.JumpBranchInfoNdPort
import pipeline.ctrl.bundles.PipelineControlNDPort

class Pc extends Module {
  val io = IO(new Bundle {
    val pc     = Output(UInt(Width.Reg.data))
    val isNext = Input(Bool())
    // `ExeStage` -> `Pc` (no delay)
    val branchSetPort = Input(new JumpBranchInfoNdPort)
    // 异常处理
    val pipelineControlPort = Input(new PipelineControlNDPort)
    val flushNewPc          = Input(UInt(Width.Reg.data))
  })

  val pcReg = RegInit(zeroWord)
  io.pc := pcReg

  pcReg := pcReg
  when(io.pipelineControlPort.flush) {
    pcReg := io.flushNewPc
  }.elsewhen(io.branchSetPort.en) {
    pcReg := io.branchSetPort.pcAddr
  }.elsewhen(io.isNext) {
    pcReg := pcReg + 4.U
  }
}
