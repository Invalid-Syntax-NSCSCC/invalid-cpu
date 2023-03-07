package pipeline.dispatch.bundles

import chisel3._
import common.bundles.RfAccessInfoNdPort
import spec._

class DecodeOutNdPort extends Bundle {
  // Is instruction matched
  val isMatched = Bool()

  val info = new PreMicrocodeNdPort
}
