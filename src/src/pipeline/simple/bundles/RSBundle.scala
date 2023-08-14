package pipeline.simple.bundles

import chisel3._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import pipeline.simple.id.FetchInstDecodeNdPort
import spec._

class RSBundle extends Bundle {
  val decodePort     = new FetchInstDecodeNdPort
  val regReadResults = Vec(Param.regFileReadNum, Valid(UInt(Width.Reg.data)))
}

object RSBundle {
  val default = 0.U.asTypeOf(new RSBundle)
}

class MainRSBundle extends RSBundle {
  val mainExeBranchInfo = new MainExeBranchInfoBundle
}

class RSBundle2 extends Bundle {
  val instInfo       = new InstInfoNdPort
  val gprReadPorts   = Vec(Param.regFileReadNum, new RfAccessInfoNdPort)
  val gprWritePort   = new RfAccessInfoNdPort
  val regReadResults = Vec(Param.regFileReadNum, Valid(UInt(Width.Reg.data)))
}

class MainRSBundle2 extends RSBundle2 {
  val mainExeBranchInfo = new MainExeBranchInfoBundle
}
