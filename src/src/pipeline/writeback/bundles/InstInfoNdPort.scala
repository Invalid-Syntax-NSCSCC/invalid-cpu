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

  val load  = new DifftestLoadNdPort
  val store = new DifftestStoreNdPort
}

object InstInfoNdPort {
  def default = (new InstInfoNdPort).Lit(
    _.pc -> zeroWord,
    _.inst -> zeroWord,
    _.exceptionRecords -> Vec.Lit(
      Seq.fill(Csr.ExceptionIndex.width)(false.B): _*
    ),
    _.csrWritePort -> CsrWriteNdPort.default,
    _.exeOp -> ExeInst.Op.nop,
    _.robId -> zeroWord
  )

  def invalidate(instInfo: InstInfoNdPort): Unit = {
    instInfo.isValid := false.B
    instInfo.exceptionRecords.foreach(_ := false.B)
    instInfo.exeOp           := ExeInst.Op.nop
    instInfo.csrWritePort.en := false.B
    instInfo.load.en         := false.B
    instInfo.store.en        := false.B
  }
}
