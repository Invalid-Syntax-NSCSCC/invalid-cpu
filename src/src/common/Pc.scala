package common

import chisel3._
import chisel3.util._
import common.bundles._
import spec._
import pipeline.execution.bundles.JumpBranchInfoNdPort
import control.bundles.PipelineControlNDPort

// attention: 从cache不一定能一次性全部取出，待修改
class Pc(
  val issueNum: Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    val pc     = Output(UInt(Width.Reg.data))
    val isNext = Input(Bool())
    // 异常处理
    val newPc = Input(new PcSetPort)
  })

  val pcReg = RegInit(spec.Pc.init)
  io.pc := pcReg

  pcReg := pcReg
  when(io.newPc.en) {
    pcReg := io.newPc.pcAddr
  }.elsewhen(io.isNext) {
    pcReg := pcReg + (4 * issueNum).U
  }
}
