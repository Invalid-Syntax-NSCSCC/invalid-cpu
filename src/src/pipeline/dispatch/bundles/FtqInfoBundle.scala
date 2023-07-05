package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._
import chisel3.util.log2Ceil

class FtqInfoBundle extends Bundle {
  val isLastInBlock = Bool()
  val ftqId         = UInt(Param.BPU.Width.id)
  val idxInBlock    = UInt(log2Ceil(Param.fetchInstMaxNum).W)
  val predictBranch = Bool()
}

object FtqInfoBundle extends Bundle {
  def default = 0.U.asTypeOf(new FtqInfoBundle)
}
