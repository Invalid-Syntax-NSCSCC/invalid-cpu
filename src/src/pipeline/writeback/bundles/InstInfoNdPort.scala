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

  val load = new Bundle {
    val en    = UInt(8.W) // {2'b0, inst_ll_w, inst_ld_w, inst_ld_hu, inst_ld_h, inst_ld_bu, inst_ld_b}
    val vaddr = UInt(32.W)
    val paddr = UInt(32.W)
  }
  val store = new Bundle {
    val en    = UInt(8.W) // {4'b0, ds_llbit && inst_sc_w, inst_st_w, inst_st_h, inst_st_b}
    val vaddr = UInt(32.W)
    val paddr = UInt(32.W)
    val data  = UInt(32.W)
  }
}

object InstInfoNdPort {
  def default = 0.U.asTypeOf(new InstInfoNdPort)

  def invalidate(instInfo: InstInfoNdPort): Unit = {
    instInfo.isValid := false.B
    instInfo.exceptionRecords.foreach(_ := false.B)
    instInfo.exeOp           := ExeInst.Op.nop
    instInfo.csrWritePort.en := false.B
    instInfo.load.en         := false.B
    instInfo.store.en        := false.B
  }
}
