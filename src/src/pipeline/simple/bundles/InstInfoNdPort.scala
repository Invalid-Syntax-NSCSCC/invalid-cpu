package pipeline.simple.bundles

import chisel3._
import control.bundles._
import control.enums.ExceptionPos
import spec.Param.isDiffTest
import pipeline.common.bundles.{CommitFtqInfoBundle, _}
import spec._

class InstInfoNdPort extends Bundle {
  val isValid         = Bool()
  val exceptionPos    = ExceptionPos()
  val exceptionRecord = UInt(Csr.ExceptionIndex.width)
  val isStore         = Bool()
  val needRefetch     = Bool()
  val isCsrWrite      = Bool()

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

    if (isDiffTest) {
      instInfo.load.get.en  := false.B
      instInfo.store.get.en := false.B
    }
  }
}
