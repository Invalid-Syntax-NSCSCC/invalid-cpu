package common

import chisel3._
import chisel3.util._
import common.bundles._
import spec._
import pipeline.execution.bundles.JumpBranchInfoNdPort

class Pc extends Module {
  val io = IO(new Bundle {
    val pc     = Output(UInt(Width.Reg.data))
    val isNext = Input(Bool())
    // `ExeStage` -> `Pc` (no delay)
    val branchSetPort = Output(new JumpBranchInfoNdPort)
  })

  val pcReg = RegInit(zeroWord)
  io.pc := pcReg

  pcReg := pcReg
  when(io.branchSetPort.en) {
    pcReg := io.branchSetPort.pcAddr
  }.elsewhen(io.isNext) {
    pcReg := pcReg + 4.U
  }
}
