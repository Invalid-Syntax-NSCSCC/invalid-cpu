package pipeline.commit.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import common.bundles.PcSetPort
import control.bundles.CsrWriteNdPort
import control.enums.ExceptionPos
import spec.Param.isDiffTest
import spec._

class InstInfoNdPort extends Bundle {
  val isValid         = Bool()
  val pc              = UInt(Width.Reg.data)
  val inst            = UInt(Width.Reg.data)
  val exceptionPos    = ExceptionPos()
  val exceptionRecord = UInt(Csr.ExceptionIndex.width)
  val isStore         = Bool()
  val vaddr           = UInt(Width.Mem.addr)
  val needCsr         = Bool()
  val csrWritePort    = new CsrWriteNdPort
  val branchSuccess   = Bool()

  val exeOp  = UInt(Param.Width.exeOp)
  val exeSel = UInt(Param.Width.exeSel)
  val robId  = UInt(Param.Width.Rob.id)

  val load    = if (isDiffTest) Some(new DifftestLoadNdPort) else None
  val store   = if (isDiffTest) Some(new DifftestStoreNdPort) else None
  val tlbFill = if (isDiffTest) Some(new DifftestTlbFillNdPort) else None

  val isTlb = Bool()

  val forbidParallelCommit = Bool()
}

object InstInfoNdPort {
  def default = if (isDiffTest)
    (new InstInfoNdPort).Lit(
      _.isValid -> false.B,
      _.pc -> zeroWord,
      _.inst -> zeroWord,
      _.exceptionPos -> ExceptionPos.none,
      _.exceptionRecord -> 0.U,
      _.csrWritePort -> CsrWriteNdPort.default,
      _.exeOp -> ExeInst.Op.nop,
      _.exeSel -> ExeInst.Sel.none,
      _.robId -> zeroWord,
      _.isStore -> false.B,
      _.vaddr -> zeroWord,
      _.needCsr -> false.B,
      _.load.get -> DifftestLoadNdPort.default,
      _.store.get -> DifftestStoreNdPort.default,
      _.tlbFill.get -> DifftestTlbFillNdPort.default,
      _.isTlb -> false.B,
      _.forbidParallelCommit -> false.B,
      _.branchSuccess -> false.B
    )
  else
    (new InstInfoNdPort).Lit(
      _.isValid -> false.B,
      _.pc -> zeroWord,
      _.inst -> zeroWord,
      _.exceptionPos -> ExceptionPos.none,
      _.exceptionRecord -> 0.U,
      _.csrWritePort -> CsrWriteNdPort.default,
      _.exeOp -> ExeInst.Op.nop,
      _.exeSel -> ExeInst.Sel.none,
      _.robId -> zeroWord,
      _.isStore -> false.B,
      _.vaddr -> zeroWord,
      _.needCsr -> false.B,
      _.isTlb -> false.B,
      _.forbidParallelCommit -> false.B,
      _.branchSuccess -> false.B
    )

  def invalidate(instInfo: InstInfoNdPort): Unit = {
    instInfo.isValid         := false.B
    instInfo.needCsr         := false.B
    instInfo.exceptionRecord := 0.U
    instInfo.exceptionPos    := ExceptionPos.none
    instInfo.exeOp           := ExeInst.Op.nop
    instInfo.exeSel          := ExeInst.Sel.none
    instInfo.csrWritePort.en := false.B
    if (isDiffTest) {
      instInfo.load.get.en  := false.B
      instInfo.store.get.en := false.B
    }
    instInfo.isTlb                := false.B
    instInfo.isStore              := false.B
    instInfo.forbidParallelCommit := false.B
    instInfo.branchSuccess        := false.B
  }
}
