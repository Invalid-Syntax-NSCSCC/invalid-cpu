package newpipeline.rob.bundles

import chisel3._
import spec._

class NewRobOccupyNdPort extends Bundle {
  val valid = Bool()
  val addr  = UInt(Width.Reg.addr)
  val robId = UInt(Param.Width.Rob.id)
}
