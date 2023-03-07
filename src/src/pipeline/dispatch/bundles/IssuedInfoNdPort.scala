package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._

class IssuedInfoNdPort extends Bundle {
  val isValid = Bool()

  val info = new PreMicrocodeNdPort
}
