package pipeline.complex.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import pipeline.common.enums.RobDistributeSel
import spec._

class RobDistributeBundle extends Bundle {
  val sel    = RobDistributeSel()
  val result = UInt(Width.Reg.data)
}

object RobDistributeBundle {
  def default = (new RobDistributeBundle).Lit(
    _.sel -> RobDistributeSel.realData,
    _.result -> 0.U
  )
}
