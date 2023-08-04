package pipeline.complex.rob.bundles

import chisel3._
import pipeline.common.enums.RegDataState
import spec._

class RobMatchBundle extends Bundle {
  val state = RegDataState()
  // val robId = UInt(Param.Width.Rob.id)
  val data = UInt(Width.Reg.data)
}

object RobMatchBundle {
  def default = 0.U.asTypeOf(new RobMatchBundle)
}
