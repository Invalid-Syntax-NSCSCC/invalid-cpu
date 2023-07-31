package frontend.bundles

import chisel3._
import spec.Param

class PreDecoderResultNdPort extends Bundle {
  val isImmJump      = Bool()
  val branchType     = UInt(Param.BPU.BranchType.width.W)
  val jumpTargetAddr = UInt(spec.Width.Reg.data)
}

object PreDecoderResultNdPort {
  def default = 0.U.asTypeOf(new PreDecoderResultNdPort)
}
