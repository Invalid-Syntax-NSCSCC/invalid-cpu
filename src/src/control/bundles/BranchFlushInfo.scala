package control.bundles

import chisel3._
import spec._

class BranchFlushInfo extends Bundle {
  val en    = Bool()
  val robId = UInt(Param.Width.Rob.id)
}

object BranchFlushInfo {
  def default = 0.U.asTypeOf(new BranchFlushInfo)
}
