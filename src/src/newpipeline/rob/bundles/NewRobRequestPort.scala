package newpipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import common.bundles.RfAccessInfoNdPort
import spec._
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.commit.bundles.PcInstBundle
import chisel3.util.Valid

class NewRobRequestPort extends Bundle {
  val request = Input(Valid(new PcInstBundle))
  val result  = Output(Valid(UInt(Param.Width.Rob.id)))
}
