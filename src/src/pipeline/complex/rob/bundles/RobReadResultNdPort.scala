package pipeline.complex.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import spec._

class RobReadResultNdPort extends Bundle {
  val robId       = UInt(Param.Width.Rob.id)
  val readResults = Vec(Param.regFileReadNum, new RobDistributeBundle)
}

object RobReadResultNdPort {
  def default = (new RobReadResultNdPort).Lit(
    _.robId -> 0.U,
    _.readResults -> Vec.Lit(Seq.fill(Param.regFileReadNum)(RobDistributeBundle.default): _*)
  )
}