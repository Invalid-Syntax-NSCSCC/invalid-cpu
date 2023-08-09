package frontend.bpu.components.Bundles

import chisel3._
import chisel3.util._
import spec._

class TageMetaPort(
  tagComponentNum:      Int = Param.BPU.TagePredictor.tagComponentNum,
  tagComponentTagWidth: Int = Param.BPU.TagePredictor.tagComponentTagWidth)
    extends Bundle {
  val providerId             = UInt(log2Ceil(tagComponentNum + 1).W)
  val altProviderId          = UInt(log2Ceil(tagComponentNum + 1).W)
  val isUseful               = Bool()
  val providerCtrBits        = Vec(tagComponentNum + 1, UInt(3.W))
  val tagPredictorQueryTags  = Vec(tagComponentNum, UInt(tagComponentTagWidth.W))
  val tagPredictorOriginTags = Vec(tagComponentNum, UInt(tagComponentTagWidth.W))
  val tagPredictorHitIndexs  = Vec(tagComponentNum, UInt(10.W))
  val tagPredictorUsefulBits = Vec(tagComponentNum, UInt(3.W))
  // global history info
  val checkPtr = UInt(Param.BPU.ftqPtrWidth.W)
}

object TageMetaPort {
  def default = 0.U.asTypeOf(new TageMetaPort)
}
