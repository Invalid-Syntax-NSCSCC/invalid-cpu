package pipeline.simple.bundles

import chisel3._
import common.bundles.RfWriteNdPort

class WbNdPort extends Bundle {
  val gprWrite = new RfWriteNdPort
  val instInfo = new InstInfoNdPort
}

object WbNdPort {
  def default = 0.U.asTypeOf(new WbNdPort)
}
