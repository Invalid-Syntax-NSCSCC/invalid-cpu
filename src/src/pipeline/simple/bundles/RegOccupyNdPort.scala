package pipeline.simple.bundles

import chisel3._
import spec.Width
import spec.Param

class RegOccupyNdPort extends Bundle {
  val en    = Bool()
  val addr  = UInt(Width.Reg.addr)
  val robId = UInt(Param.Width.Rob.id)
}
