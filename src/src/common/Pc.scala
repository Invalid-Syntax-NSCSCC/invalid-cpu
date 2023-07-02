package common

import chisel3._
import chisel3.util._
import common.bundles._
import spec._

// attention: 从cache不一定能一次性全部取出，待修改
class Pc(
  val issueNum: Int = Param.issueInstInfoMaxNum)
    extends Module {
  val io = IO(new Bundle {
    val pc = Output(UInt(Width.Reg.data))

    val ftqFull = Input(Bool())
    // 异常处理 + 分支跳转
    val newPc = Input(new BackendRedirectPcNdPort)
  })

  val pcReg = RegInit(spec.Pc.init)
  io.pc := pcReg

  // keep pc signal
  val ftqFullReg = RegNext(io.ftqFull)

  // TODO change to fixed fetchNum
  // sequential pc
  val pcFetchNum = WireDefault(Param.fetchInstMaxNum.U(log2Ceil(Param.fetchInstMaxNum + 1).W))
  if (Param.fetchInstMaxNum != 1) {
    when(pcReg(Param.Width.ICache._fetchOffset, Param.Width.ICache._instOffset) =/= 0.U) {
      pcFetchNum := Param.fetchInstMaxNum.U - pcReg(Param.Width.ICache._fetchOffset - 1, Param.Width.ICache._instOffset)
    }
  }

  when(io.cuNewPc.en) {
    // when predict error or pc error => jump
    pcReg := io.cuNewPc.pcAddr
  }.elsewhen(io.ftqFull || ftqFullReg) {
    pcReg := pcReg
  }.elsewhen(io.mainRedirectPc.valid) {
    // bpu reditect when it can predict
    pcReg := io.mainRedirectPc.valid
  }.otherwise {
    // sequential pc
    pcReg := pcReg + 4.U * pcFetchNum.asUInt
  }
}
