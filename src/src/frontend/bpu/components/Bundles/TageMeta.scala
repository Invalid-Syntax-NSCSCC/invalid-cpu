package frontend.bpu.components.Bundles

import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class TageMeta(
  tagComponentNum:      Int = Param.BPU.TagePredictor.tagComponentNum,
  tagComponentTagWidth: Int = Param.BPU.TagePredictor.tagComponentTagWidth)
    extends Bundle {
  val providerId             = UInt(log2Ceil(tagComponentNum).W)
  val altProviderId          = UInt(log2Ceil(tagComponentNum).W)
  val useful                 = Bool()
  val providerCtrBits        = Vec(tagComponentNum, UInt(3.W))
  val tagPredictorQueryTag   = Vec(tagComponentNum, UInt(tagComponentTagWidth.W))
  val tagPredictorOriginTag  = Vec(tagComponentNum, UInt(tagComponentTagWidth.W))
  val tagPredictorHitIndex   = Vec(tagComponentNum, UInt(10.W))
  val tagPredictorUsefulBits = Vec(tagComponentNum, UInt(3.W))
}

object TageMeta {
  val tagComponentNum      = Param.BPU.TagePredictor.tagComponentNum
  val tagComponentTagWidth = Param.BPU.TagePredictor.tagComponentTagWidth
  def default              = 0.U.asTypeOf(new TageMetaPort)
}
