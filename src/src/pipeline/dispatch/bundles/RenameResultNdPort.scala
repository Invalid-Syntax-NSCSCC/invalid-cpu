package pipeline.dispatch.bundles

import chisel3._
import common.bundles.RfAccessInfoNdPort
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class RenameResultNdPort(
  regReadNum:   Int                   = 2,
  grfAddrWidth: internal.firrtl.Width = 6.W)
    extends Bundle {
  val grfWritePort = new RfAccessInfoNdPort(grfAddrWidth)
  val grfReadPorts = Vec(regReadNum, new RfAccessInfoNdPort(grfAddrWidth))
}

object RenameResultNdPort {
  def setDefault(port: RenameResultNdPort) {
    port.grfWritePort := RfAccessInfoNdPort.default
    port.grfReadPorts.foreach(_ := RfAccessInfoNdPort.default)
  }
}
