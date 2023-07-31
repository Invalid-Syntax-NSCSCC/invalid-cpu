package frontend.bundles

import chisel3._
import spec.Param

class PreDecoderResultNdPort extends Bundle {
  val isJump       = Bool()
  val isImmJump      = Bool()
  val isCall         = Bool()
  val isRet          = Bool()
  val jumpTargetAddr = UInt(spec.Width.Reg.data)
}

object PreDecoderResultNdPort {
  def default = 0.U.asTypeOf(new PreDecoderResultNdPort)
}
