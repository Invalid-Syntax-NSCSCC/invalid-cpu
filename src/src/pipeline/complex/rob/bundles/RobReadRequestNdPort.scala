package pipeline.complex.rob.bundles

import chisel3._
import common.bundles.RfAccessInfoNdPort
import pipeline.common.bundles.PcInstBundle
import spec._

class RobReadRequestNdPort extends Bundle {
  val writeRequest = new RfAccessInfoNdPort
  val readRequests = Vec(Param.regFileReadNum, new RfAccessInfoNdPort)
  val fetchInfo    = new PcInstBundle
}

object RobReadRequestNdPort {
  def default = 0.U.asTypeOf(new RobReadRequestNdPort)
}
