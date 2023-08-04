package pipeline.complex.rob.bundles

import chisel3._
import spec._

class InstWbNdPort extends Bundle {
  val en    = Bool()
  val robId = UInt(Param.Width.Rob.id)
  val data  = UInt(Width.Reg.data)
}

object InstWbNdPort {
  def default = 0.U.asTypeOf(new InstWbNdPort)
}