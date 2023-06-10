package pipeline.rob.bundles

import pipeline.rob.enums.RobInstState
import common.bundles.RfWriteNdPort
import chisel3._
import chisel3.experimental.BundleLiterals._
import pipeline.writeback.WbNdPort
import spec._

class RobInstStoreBundle extends Bundle {
  val state   = RobInstState()
  val isValid = Bool()
  val wbPort  = new WbNdPort
}

object RobInstStoreBundle {
  val default = (new RobInstStoreBundle).Lit(
    _.state -> RobInstState.empty,
    _.isValid -> false.B,
    _.wbPort -> WbNdPort.default
  )
}
