package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import chisel3.util._
import spec._
import common.bundles.RfAccessInfoNdPort
import pipeline.rob.enums.RobDistributeSel

class RobReadResultNdPort extends Bundle {
  val robId       = UInt(Param.Width.Rob.id)
  val readResults = Vec(Param.regFileReadNum, new RobDistributeBundle)
}

object RobReadResultNdPort {
  val default = (new RobReadResultNdPort).Lit(
    _.robId -> 0.U,
    _.readResults -> Vec.Lit(Seq.fill(Param.regFileReadNum)(RobDistributeBundle.default): _*)
  )
}
