package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._

class IssuedInfoNdPort extends Bundle {
  val isValid = Bool()

  val info = new PreExeInstNdPort
}

object IssuedInfoNdPort {
  def default = (new IssuedInfoNdPort).Lit(
    _.isValid -> false.B,
    _.info -> PreExeInstNdPort.default
  )
}
