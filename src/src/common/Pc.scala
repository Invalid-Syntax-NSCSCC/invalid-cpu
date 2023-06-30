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
    val pc       = Output(UInt(Width.Reg.data))
    val pcUpdate = Output(Bool())
    val isNext   = Input(Bool())
    // 异常处理 + 分支跳转
    val newPc = Input(new PcSetNdPort)
  })

  val pcReg       = RegInit(spec.Pc.init)
  val pcUpdateReg = RegInit(false.B)
  io.pc       := pcReg
  io.pcUpdate := pcUpdateReg && !io.newPc.en
  val pcFetchNum = WireDefault(Param.fetchInstMaxNum.U(log2Ceil(Param.fetchInstMaxNum + 1).W))
  if (Param.fetchInstMaxNum != 1) {
    when(pcReg(Param.Width.ICache._fetchOffset, Param.Width.ICache._instOffset) =/= 0.U) {
      pcFetchNum := Param.fetchInstMaxNum.U - pcReg(Param.Width.ICache._fetchOffset - 1, Param.Width.ICache._instOffset)
    }
  }

  pcReg := pcReg
  when(io.newPc.en) {
    pcReg       := io.newPc.pcAddr
    pcUpdateReg := true.B
  }.elsewhen(io.isNext) {
    pcReg       := pcReg + 4.U * pcFetchNum.asUInt
    pcUpdateReg := true.B
  }.otherwise {
    pcReg       := pcReg
    pcUpdateReg := false.B
  }
}
