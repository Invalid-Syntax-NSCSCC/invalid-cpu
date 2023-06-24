package pipeline.commit.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import common.bundles.PcSetPort
import control.bundles.CsrWriteNdPort
import control.enums.ExceptionPos
import memory.bundles.TlbMaintenanceNdPort
import spec._

class InstInfoNdPort extends Bundle {
  val isValid         = Bool()
  val pc              = UInt(Width.Reg.data)
  val inst            = UInt(Width.Reg.data)
  val exceptionPos    = ExceptionPos()
  val exceptionRecord = UInt(Csr.ExceptionIndex.width) // Vec(Csr.ExceptionIndex.count + 1, Bool())
  val needCsr         = Bool()
  val csrWritePort    = new CsrWriteNdPort

  val exeOp  = UInt(Param.Width.exeOp)
  val exeSel = UInt(Param.Width.exeSel)
  val robId  = UInt(Param.Width.Rob.id)

  val branchSetPort = Output(new PcSetPort)

  val load  = new DifftestLoadNdPort
  val store = new DifftestStoreNdPort

  val tlbMaintenancePort = new TlbMaintenanceNdPort
}

object InstInfoNdPort {
  def default = (new InstInfoNdPort).Lit(
    _.isValid -> false.B,
    _.pc -> zeroWord,
    _.inst -> zeroWord,
    _.exceptionPos -> ExceptionPos.none,
    _.exceptionRecord -> 0.U,
    _.csrWritePort -> CsrWriteNdPort.default,
    _.exeOp -> ExeInst.Op.nop,
    _.exeSel -> ExeInst.Sel.none,
    _.robId -> zeroWord,
    _.tlbMaintenancePort -> TlbMaintenanceNdPort.default,
    _.needCsr -> false.B,
    _.branchSetPort -> PcSetPort.default,
    _.load -> DifftestLoadNdPort.default,
    _.store -> DifftestStoreNdPort.default
  )

  def invalidate(instInfo: InstInfoNdPort): Unit = {
    instInfo.isValid            := false.B
    instInfo.needCsr            := false.B
    instInfo.exceptionRecord    := 0.U
    instInfo.exceptionPos       := ExceptionPos.none
    instInfo.exeOp              := ExeInst.Op.nop
    instInfo.exeSel             := ExeInst.Sel.none
    instInfo.csrWritePort.en    := false.B
    instInfo.load.en            := false.B
    instInfo.store.en           := false.B
    instInfo.tlbMaintenancePort := TlbMaintenanceNdPort.default
    instInfo.branchSetPort.en   := false.B
  }
}
