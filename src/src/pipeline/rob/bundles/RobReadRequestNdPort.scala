package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import common.bundles.RfAccessInfoNdPort
import spec._
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.commit.bundles.PcInstBundle

class RobReadRequestNdPort extends Bundle {
  val writeRequest = new RfAccessInfoNdPort
  val readRequests = Vec(Param.regFileReadNum, new RfAccessInfoNdPort)
  val fetchInfo    = new PcInstBundle
}

object RobReadRequestNdPort {
  def default = 0.U.asTypeOf(new RobReadRequestNdPort)
}
