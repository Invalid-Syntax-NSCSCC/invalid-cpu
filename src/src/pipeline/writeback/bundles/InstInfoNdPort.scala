package pipeline.writeback.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import chisel3.util._
import spec._
import control.bundles.CsrWriteNdPort
import memory.bundles.TlbMaintenanceNdPort
import common.bundles.PcSetPort

class InstInfoNdPort extends Bundle {
  val isValid          = Bool()
  val pc               = UInt(Width.Reg.data)
  val inst             = UInt(Width.Reg.data)
  val isExceptionValid = Bool()
  val exceptionRecord  = UInt(Csr.ExceptionIndex.width) // Vec(Csr.ExceptionIndex.count + 1, Bool())
  val needCsr          = Bool()
  val csrWritePort     = new CsrWriteNdPort

  val exeOp  = UInt(Param.Width.exeOp)
  val exeSel = UInt(Param.Width.exeSel)
  val robId  = UInt(Param.Width.Rob.id)

  val branchSetPort = Output(new PcSetPort)

  val load  = new DifftestLoadNdPort
  val store = new DifftestStoreNdPort

  val tlbInfo = new TlbMaintenanceNdPort
}

object InstInfoNdPort {
  def default = (new InstInfoNdPort).Lit(
    _.isValid -> false.B,
    _.pc -> zeroWord,
    _.inst -> zeroWord,
    _.isExceptionValid -> false.B,
    _.exceptionRecord -> 0.U,
    _.csrWritePort -> CsrWriteNdPort.default,
    _.exeOp -> ExeInst.Op.nop,
    _.exeSel -> ExeInst.Sel.none,
    _.robId -> zeroWord,
    _.tlbInfo -> TlbMaintenanceNdPort.default,
    _.needCsr -> false.B,
    _.branchSetPort -> PcSetPort.default,
    _.load -> DifftestLoadNdPort.default,
    _.store -> DifftestStoreNdPort.default
  )

  def invalidate(instInfo: InstInfoNdPort): Unit = {
    instInfo.isValid          := false.B
    instInfo.needCsr          := false.B
    instInfo.exceptionRecord  := 0.U
    instInfo.isExceptionValid := false.B
    instInfo.exeOp            := ExeInst.Op.nop
    instInfo.exeSel           := ExeInst.Sel.none
    instInfo.csrWritePort.en  := false.B
    instInfo.load.en          := false.B
    instInfo.store.en         := false.B
    instInfo.tlbInfo          := TlbMaintenanceNdPort.default
    instInfo.branchSetPort.en := false.B
  }
}
