package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import spec._
import common.bundles.RfAccessInfoNdPort
import pipeline.rob.bundles.RobReadRequestNdPort

class RobReadRequestNdPort extends Bundle {
  val writeRequest = new RfAccessInfoNdPort
  val readRequests = Vec(Param.regFileReadNum, new RfAccessInfoNdPort)
}

object RobReadRequestNdPort {
  val default = 0.U.asTypeOf(new RobReadRequestNdPort)
}
