package pipeline.simple.bundles

import chisel3._
import spec.Width
import spec.Param

class RegWakeUpNdPort extends Bundle {
  val en    = Bool()
  val addr  = UInt(Width.Reg.addr)
  val data  = UInt(Width.Reg.data)
  val robId = UInt(Param.Width.Rob.id)
}

object RegWakeUpNdPort {
  def default = 0.U.asTypeOf(new RegWakeUpNdPort)
}
