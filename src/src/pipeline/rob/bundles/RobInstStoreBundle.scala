package pipeline.rob.bundles

import chisel3._
import spec.Width
import pipeline.commit.WbNdPort
import pipeline.rob.enums.RobInstState
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.commit.bundles.PcInstBundle

class RobInstStoreBundle extends Bundle {
  val state     = RobInstState()
  val wbPort    = new WbNdPort
  val fetchInfo = new PcInstBundle
}

object RobInstStoreBundle {
  def default = 0.U.asTypeOf(new RobInstStoreBundle)
}
