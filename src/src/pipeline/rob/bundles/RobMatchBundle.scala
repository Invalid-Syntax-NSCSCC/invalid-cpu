package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import pipeline.rob.enums.RegDataState
import spec._
import chisel3.util.Valid

class RobMatchBundle extends Bundle {
  val state = RegDataState()
  // val robId = UInt(Param.Width.Rob.id)
  val data = UInt(Width.Reg.data)
}

object RobMatchBundle {
  def default = 0.U.asTypeOf(new RobMatchBundle)
}
