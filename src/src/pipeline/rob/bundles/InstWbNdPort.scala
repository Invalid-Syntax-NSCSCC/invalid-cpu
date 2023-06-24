package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import spec._
import common.bundles.RfAccessInfoNdPort

class InstWbNdPort extends Bundle {
  val en    = Bool()
  val robId = UInt(Param.Width.Rob.id)
  val data  = UInt(Width.Reg.data)
}

object InstWbNdPort {
  def default = 0.U.asTypeOf(new InstWbNdPort)
}
