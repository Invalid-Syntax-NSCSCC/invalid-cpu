package pipeline.simple.bundles

import chisel3._
import spec._

class RobOccupyNdPort extends Bundle {
  val valid = Bool()
  val addr  = UInt(Width.Reg.addr)
  val robId = UInt(Param.Width.Rob.id)
}
