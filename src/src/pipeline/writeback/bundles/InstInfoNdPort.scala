package pipeline.writeback.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import chisel3.util._
import spec._
import control.bundles.CsrWriteNdPort

class InstInfoNdPort extends Bundle {
  val pc               = UInt(Width.Reg.data)
  val inst             = UInt(Width.Reg.data)
  val exceptionRecords = Vec(Csr.ExceptionIndex.count + 1, Bool())
  val csrWritePort     = new CsrWriteNdPort

  val exeOp = UInt(Param.Width.exeOp)
  val robId = UInt(Param.robIdLength.W)
}

object InstInfoNdPort {
  def default = (new InstInfoNdPort).Lit(
    _.pc -> zeroWord,
    _.inst -> zeroWord,
    _.exceptionRecords -> Vec.Lit(
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B
    ),
    _.csrWritePort -> CsrWriteNdPort.default,
    _.exeOp -> ExeInst.Op.nop,
    _.robId -> zeroWord
  )

  def invalidate(instInfo: InstInfoNdPort): Unit = {
    instInfo.pc   := 0.U
    instInfo.inst := 0.U
    instInfo.exceptionRecords.foreach(_ := false.B)
    instInfo.exeOp           := ExeInst.Op.nop
    instInfo.csrWritePort.en := false.B
  }
}
