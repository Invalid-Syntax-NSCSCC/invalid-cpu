package pipeline.writeback.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import chisel3.util._
import spec._
import control.bundles.CsrWriteNdPort

class InstInfoNdPort extends Bundle {
  val isValid          = Bool()
  val pc               = UInt(Width.Reg.data)
  val inst             = UInt(Width.Reg.data)
  val exceptionRecords = Vec(Csr.ExceptionIndex.count + 1, Bool())
  val csrWritePort     = new CsrWriteNdPort

  val exeOp = UInt(Param.Width.exeOp)
  val robId = UInt(Param.robIdLength.W)
}

object InstInfoNdPort {
  def default = 0.U.asTypeOf(new InstInfoNdPort)

  def invalidate(instInfo: InstInfoNdPort): Unit = {
    instInfo.isValid := false.B
    instInfo.exceptionRecords.foreach(_ := false.B)
    instInfo.exeOp           := ExeInst.Op.nop
    instInfo.csrWritePort.en := false.B
  }
}
