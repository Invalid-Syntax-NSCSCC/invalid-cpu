package frontend.bundles

import chisel3._

class PreDecoderResultNdPort extends Bundle {
  val isUnconditionalJump = Bool()
  val isRegJump           = Bool()
  val jumpTargetAddr      = UInt(spec.Width.Reg.data)
}

object PreDecoderResultNdPort {
  val default = 0.U.asTypeOf(new PreDecoderResultNdPort)
}
