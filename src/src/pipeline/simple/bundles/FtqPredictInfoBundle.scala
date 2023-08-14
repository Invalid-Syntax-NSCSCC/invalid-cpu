package pipeline.simple.bundles

import chisel3._
import spec._
import chisel3.util._

class FtqPredictInfoBundle extends Bundle {

  val idxInBlock     = UInt(log2Ceil(Param.fetchInstMaxNum).W)
  val predictBranch  = Bool()
  val isPredictValid = Bool()
}
