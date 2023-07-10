package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import common.bundles.RfAccessInfoNdPort
import spec._

class RobReadRequestNdPort extends Bundle {
  // val en           = Bool()
  val writeRequest = new RfAccessInfoNdPort
  val readRequests = Vec(Param.regFileReadNum, new RfAccessInfoNdPort)
}

object RobReadRequestNdPort {
  def default = (new RobReadRequestNdPort).Lit(
    // _.en -> false.B,
    _.writeRequest -> RfAccessInfoNdPort.default,
    _.readRequests -> Vec.Lit(Seq.fill(Param.regFileReadNum)(RfAccessInfoNdPort.default): _*)
  )
}
