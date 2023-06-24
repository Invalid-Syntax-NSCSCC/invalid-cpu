package pipeline.queue.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import pipeline.dispatch.bundles.PreExeInstNdPort

class DecodeOutNdPort extends Bundle {
  // Is instruction matched
  val isMatched = Bool()

  val info = new PreExeInstNdPort
}

object DecodeOutNdPort {
  def default = (new DecodeOutNdPort).Lit(
    _.isMatched -> false.B,
    _.info -> PreExeInstNdPort.default
  )
}
