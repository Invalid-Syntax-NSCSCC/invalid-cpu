package pipeline.simple.bundles

import chisel3._
import pipeline.common.enums.RobInstState
import pipeline.simple.bundles.WbNdPort
import pipeline.common.bundles.PcInstBundle

class RobInstStoreBundle extends Bundle {
  val state     = RobInstState()
  val wbPort    = new WbNdPort
  val fetchInfo = new PcInstBundle
}

object RobInstStoreBundle {
  def default = 0.U.asTypeOf(new RobInstStoreBundle)
}
