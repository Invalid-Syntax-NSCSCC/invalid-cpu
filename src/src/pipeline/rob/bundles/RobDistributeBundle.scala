package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import chisel3.util._
import spec._
import common.bundles.RfAccessInfoNdPort
import pipeline.rob.enums.RobDistributeSel

class RobDistributeBundle extends Bundle {
  val sel    = RobDistributeSel()
  val result = UInt(Width.Reg.data)
}

object RobDistributeBundle {
  val default = (new RobDistributeBundle).Lit(
    _.sel -> RobDistributeSel.realData,
    _.result -> 0.U
  )
}
