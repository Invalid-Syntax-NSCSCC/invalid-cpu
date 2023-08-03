package pipeline.common.bundles

import chisel3._
import pipeline.common.enums.RobInstState
import pipeline.complex.commit.WbNdPort

class RobInstStoreBundle extends Bundle {
  val state     = RobInstState()
  val wbPort    = new WbNdPort
  val fetchInfo = new PcInstBundle
}

object RobInstStoreBundle {
  def default = 0.U.asTypeOf(new RobInstStoreBundle)
}
