package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import pipeline.rob.enums.RegDataLocateSel
import spec._

class RobMatchBundle extends Bundle {
  val locate = RegDataLocateSel()
  val robId  = UInt(Param.Width.Rob.id)
}

object RobMatchBundle {
  def default = (new RobMatchBundle).Lit(
    _.locate -> RegDataLocateSel.regfile,
    _.robId -> 0.U
  )
}
