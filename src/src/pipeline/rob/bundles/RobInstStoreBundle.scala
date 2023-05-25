package pipeline.rob.bundles

import pipeline.rob.enums.RobInstState
import common.bundles.RfWriteNdPort
import chisel3._
import chisel3.experimental.BundleLiterals._

class RobInstStoreBundle(idLength: Int = 32) extends Bundle {
  val state     = RobInstState()
  val id        = UInt(idLength.W)
  val writePort = new RfWriteNdPort
}

object RobInstStoreBundle {
  val default = (new RobInstStoreBundle).Lit(
    _.state -> RobInstState.empty,
    _.writePort -> RfWriteNdPort.default,
    _.id -> 0.U
  )
}
