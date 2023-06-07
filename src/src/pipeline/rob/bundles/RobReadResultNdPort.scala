package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import spec._
import common.bundles.RfAccessInfoNdPort

class RobReadResultNdPort extends Bundle {
  val writeResult = UInt(Width.Rob.id)
  val readResults = Vec(Param.regFileReadNum, new RobDistributeBundle)
}

object RobReadResultNdPort {
  val default = 0.U.asTypeOf(new RobReadResultNdPort)
}
