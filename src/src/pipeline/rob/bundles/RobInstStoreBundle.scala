package pipeline.rob.bundles

import pipeline.rob.enums.RobInstState
import common.bundles.RfWriteNdPort
import chisel3._
import chisel3.experimental.BundleLiterals._
import pipeline.commit.WbNdPort
import spec._

class RobInstStoreBundle extends Bundle {
  val state   = RobInstState()
  val isValid = Bool()
  val wbPort  = new WbNdPort
}

object RobInstStoreBundle {
  def default = 0.U.asTypeOf(new RobInstStoreBundle)
}
