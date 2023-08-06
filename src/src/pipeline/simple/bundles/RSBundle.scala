package pipeline.simple.bundles

import spec._
import chisel3.util._
import chisel3._
import pipeline.simple.id.FetchInstDecodeNdPort

class RSBundle extends Bundle {
  val decodePort     = new FetchInstDecodeNdPort
  val regReadResults = Vec(Param.regFileReadNum, Valid(UInt(Width.Reg.data)))
}

class MainRSBundle extends RSBundle {
  val mainExeBranchInfo = new MainExeBranchInfoBundle
}
