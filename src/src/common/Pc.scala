package common

import chisel3._
import chisel3.util._
import common.bundles._
import spec._

class Pc extends Module {
  val io = IO(new Bundle {
    val pc     = Output(UInt(Width.Reg.data))
    val isNext = Input(Bool())
  })

  val pcReg = RegInit(zeroWord)
  io.pc := pcReg

  pcReg := pcReg
  when(io.isNext) {
    pcReg := pcReg + 4.U
  }
}
