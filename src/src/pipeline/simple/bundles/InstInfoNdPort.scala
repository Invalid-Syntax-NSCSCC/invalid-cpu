package pipeline.simple.bundles

import chisel3._
import control.bundles._
import control.enums.ExceptionPos
import pipeline.common.bundles._
import spec.ExeInst.OpBundle
import spec.Param.isDiffTest
import spec._

class InstInfoNdPort extends Bundle {
  val pc              = if (isDiffTest) Some(UInt(Width.Reg.data)) else None
  val inst            = if (isDiffTest) Some(UInt(Width.Reg.data)) else None
  val isValid         = Bool()
  val exceptionPos    = ExceptionPos()
  val exceptionRecord = UInt(Csr.ExceptionIndex.width)
  val needRefetch     = Bool()
  val isCsrWrite      = Bool()

  val exeOp = new OpBundle
  val robId = UInt(Param.Width.Rob.id)

  val load          = if (isDiffTest) Some(new DifftestLoadNdPort) else None
  val store         = if (isDiffTest) Some(new DifftestStoreNdPort) else None
  val tlbFill       = if (isDiffTest) Some(new DifftestTlbFillNdPort) else None
  val timerInfo     = if (isDiffTest) Some(new DifftestTimerNdPort) else None
  val ftqInfo       = new FtqStoreInfoBundle
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
    instInfo.forbidParallelCommit := false.B

    if (isDiffTest) {
      instInfo.load.get.en  := false.B
      instInfo.store.get.en := false.B
    }
  }
}
