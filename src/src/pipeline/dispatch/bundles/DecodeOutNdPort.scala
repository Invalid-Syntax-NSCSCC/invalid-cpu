package pipeline.dispatch.bundles

import chisel3._
import common.bundles.RfAccessInfoNdPort
import spec._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class DecodeOutNdPort extends Bundle {
  // Is instruction matched
  val isMatched = Bool()

  val info = new PreExeInstNdPort
}

object DecodeOutNdPort {
  val default = (new DecodeOutNdPort).Lit(
    _.isMatched -> false.B,
    _.info -> PreExeInstNdPort.default
  )
}
