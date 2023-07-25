package frontend.bpu.components.Bundles

import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

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
}

object TageMetaPort {
  val tagComponentNum      = Param.BPU.TagePredictor.tagComponentNum
  val tagComponentTagWidth = Param.BPU.TagePredictor.tagComponentTagWidth
  def default              = 0.U.asTypeOf(new TageMetaPort)
}
