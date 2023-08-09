package pipeline.common.bundles

import chisel3._
import spec._

class FtqInfoBundle extends Bundle {
  val isLastInBlock = Bool()
  val ftqId         = UInt(Param.BPU.Width.id)
  // val idxInBlock    = UInt(log2Ceil(Param.fetchInstMaxNum).W)
  val predictBranch  = Bool()
  val isPredictValid = Bool()
}

object FtqInfoBundle extends Bundle {
  def default = 0.U.asTypeOf(new FtqInfoBundle)
}
