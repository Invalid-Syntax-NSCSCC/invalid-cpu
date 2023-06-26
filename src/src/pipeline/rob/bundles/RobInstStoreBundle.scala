package pipeline.rob.bundles

import chisel3._
import pipeline.commit.WbNdPort
import pipeline.rob.enums.RobInstState

class RobInstStoreBundle extends Bundle {
  val state   = RobInstState()
  val isValid = Bool()
  val wbPort  = new WbNdPort
}

object RobInstStoreBundle {
  def default = 0.U.asTypeOf(new RobInstStoreBundle)
}
