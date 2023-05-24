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
  val default = (new InstInfoNdPort).Lit(
    _.pc -> 0.U,
    _.inst -> 0.U,
    _.exceptionRecords -> Vec.Lit(
      Seq.fill(Csr.ExceptionIndex.count + 1)(false.B): _*
    ),
    _.csrWritePort -> CsrWriteNdPort.default,
    _.exeOp -> 0.U,
    _.robId -> 0.U
  )

  def invalidate(instInfo: InstInfoNdPort): Unit = {
    instInfo.pc   := 0.U
    instInfo.inst := 0.U
    instInfo.exceptionRecords.foreach(_ := false.B)
    instInfo.exeOp           := ExeInst.Op.nop
    instInfo.csrWritePort.en := false.B
  }
}
