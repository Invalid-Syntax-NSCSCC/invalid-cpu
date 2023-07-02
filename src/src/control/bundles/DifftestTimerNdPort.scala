package control.bundles

import chisel3._
import spec.doubleWordLength

class DifftestTimerNdPort extends Bundle {
  val isCnt   = Bool()
  val timer64 = UInt(doubleWordLength.W)
}

object DifftestTimerNdPort {
  def default = 0.U.asTypeOf(new DifftestTimerNdPort)
}
