package pipeline.rob.bundles

import chisel3._
import spec._

class RobQueryPcPort extends Bundle {
  val robId = Input(UInt(Param.Width.Rob.id))
  val pc    = Output(UInt(Width.Reg.data))
}
