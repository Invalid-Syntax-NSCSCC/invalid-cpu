package pipeline.commit.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import common.bundles.BackendRedirectPcNdPort
import control.bundles._
import control.enums.ExceptionPos
import spec.Param.isDiffTest
import spec._
import pipeline.dispatch.bundles.FtqInfoBundle

class InstInfoNdPort extends Bundle {
  val isValid         = Bool()
  val pc              = UInt(Width.Reg.data)
  val inst            = UInt(Width.Reg.data)
  val exceptionPos    = ExceptionPos()
  val exceptionRecord = UInt(Csr.ExceptionIndex.width)
  val isStore         = Bool()
  val vaddr           = UInt(Width.Mem.addr)
  val needRefetch     = Bool()
  val isCsrWrite      = Bool()
  val branchSuccess   = Bool()

  val exeOp = UInt(Param.Width.exeOp)
  val robId = UInt(Param.Width.Rob.id)

  val load          = if (isDiffTest) Some(new DifftestLoadNdPort) else None
  val store         = if (isDiffTest) Some(new DifftestStoreNdPort) else None
  val tlbFill       = if (isDiffTest) Some(new DifftestTlbFillNdPort) else None
  val timerInfo     = if (isDiffTest) Some(new DifftestTimerNdPort) else None
  val ftqInfo       = new FtqInfoBundle
  val ftqCommitInfo = new CommitFtqInfoBundle

  val isTlb = Bool()

  val forbidParallelCommit = Bool()
}

object InstInfoNdPort {
  def default = 0.U.asTypeOf(new InstInfoNdPort)

  def invalidate(instInfo: InstInfoNdPort): Unit = {
    instInfo.isValid              := false.B
    instInfo.needRefetch          := false.B
    instInfo.exceptionPos         := ExceptionPos.none
    instInfo.exeOp                := ExeInst.Op.nop
    instInfo.isCsrWrite           := false.B
    instInfo.isTlb                := false.B
    instInfo.isStore              := false.B
    instInfo.forbidParallelCommit := false.B
    instInfo.branchSuccess        := false.B

    if (isDiffTest) {
      instInfo.load.get.en  := false.B
      instInfo.store.get.en := false.B
    }
  }
}
