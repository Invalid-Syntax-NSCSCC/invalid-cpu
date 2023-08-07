package frontend.bundles

import chisel3._
import spec.Param

class CommitFtqTrainNdPort extends Bundle {
  val bitMask      = Vec(Param.commitNum, Bool())
  val isTrainValid = Bool()
  val ftqId        = UInt(Param.BPU.Width.id)
  val branchTakenMeta         = new BranchTakenMetaBundle
}

object CommitFtqTrainNdPort {
  def default = 0.U.asTypeOf(new CommitFtqTrainNdPort)
}
