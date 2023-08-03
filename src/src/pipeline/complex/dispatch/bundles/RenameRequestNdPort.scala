package pipeline.complex.dispatch.bundles

import chisel3._
import common.bundles.RfAccessInfoNdPort

class RenameRequestNdPort(
  regReadNum:   Int                   = 2,
  arfAddrWidth: internal.firrtl.Width = 5.W)
    extends Bundle {
  val arfWritePort = new RfAccessInfoNdPort(arfAddrWidth)
  val arfReadPorts = Vec(regReadNum, new RfAccessInfoNdPort(arfAddrWidth))
}
