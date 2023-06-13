package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class BranchFlushInfo extends Bundle {
  val en    = Bool()
  val robId = UInt(Param.Width.Rob.id)
}

object BranchFlushInfo {
  val default = 0.U.asTypeOf(new BranchFlushInfo)
}
