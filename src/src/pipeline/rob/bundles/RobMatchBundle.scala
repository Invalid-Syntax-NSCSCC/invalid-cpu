package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import spec._
import common.bundles.RfAccessInfoNdPort
import pipeline.rob.enums.RegDataLocateSel

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
