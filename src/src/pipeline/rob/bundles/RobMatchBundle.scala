package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import pipeline.rob.enums.RegDataLocateSel
import spec._
import chisel3.util.Valid

class RobMatchBundle extends Bundle {
  val locate     = RegDataLocateSel()
  val robId      = UInt(Param.Width.Rob.id)
  val robResData = Valid(UInt(Width.Reg.data))
}

object RobMatchBundle {
  def default = 0.U.asTypeOf(new RobMatchBundle)
}
