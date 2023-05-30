package control

import chisel3._
import spec._
import chisel3.util._

class Dbar(
  issueNum:       Int = spec.Param.issueInstInfoMaxNum,
  dbarCounterNum: Int = 8)
    extends Module {
  val numLog = log2Ceil(dbarCounterNum)
  val io = IO(new Bundle {
    val startDbar       = Input(Bool())
    val startLoadStore  = Input(Bool())
    val commitDBar      = Input(Bool())
    val commitLoadStore = Input(Bool())
    val allowDBar       = Input(Bool())
    val allowLoadStore  = Output(Bool())

    val isFlush = Input(Bool())
  })

  val allowDBarReg = RegInit(true.B)
  io.allowDBar := allowDBarReg
  val allowLoadStoreReg = RegInit(true.B)
  io.allowLoadStore := allowLoadStoreReg

  val loadStoreCounter = RegInit(0.U(numLog.W))

  val counter = RegInit(0.U(numLog.W))

  when(io.startDbar) {
    allowDBarReg := false.B

  }

}
