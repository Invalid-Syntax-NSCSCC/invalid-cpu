package pipeline.rob.bundles

import pipeline.rob.enums.RobInstStage
import common.bundles.RfWriteNdPort
import chisel3.Bundle
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class RobInstStoreBundle extends Bundle {
  val state     = RobInstStage()
  val writePort = new RfWriteNdPort
}

object RobInstStoreBundle {
  val default = (new RobInstStoreBundle).Lit(
    _.state -> RobInstStage.empty,
    _.writePort -> RfWriteNdPort.default
  )
}
