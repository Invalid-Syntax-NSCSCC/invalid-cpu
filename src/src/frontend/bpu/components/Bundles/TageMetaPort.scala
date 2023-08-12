package frontend.bpu.components.Bundles

import chisel3._
import chisel3.util._
import spec._

class TageGhrInfo(
  tagComponentNum:      Int = Param.BPU.TagePredictor.tagComponentNum,
  tagComponentTagWidth: Int = Param.BPU.TagePredictor.tagComponentTagWidth,
  phtAddrWidth:         Int = log2Ceil(Param.BPU.TagePredictor.componentTableDepth(1)))
    extends Bundle {
  val checkPtr        = UInt(Param.BPU.TagePredictor.ghrPtrWidth.W)
  val tagGhtHashs     = Vec(tagComponentNum, UInt(phtAddrWidth.W))
  val tagTagHashCsr1s = Vec(tagComponentNum, UInt(tagComponentTagWidth.W))
  val tagTagHashCsr2s = Vec(tagComponentNum, UInt((tagComponentTagWidth - 1).W))
}

class TageMetaPort(
  tagComponentNum:      Int = Param.BPU.TagePredictor.tagComponentNum,
  tagComponentTagWidth: Int = Param.BPU.TagePredictor.tagComponentTagWidth,
  phtAddrWidth:         Int = log2Ceil(Param.BPU.TagePredictor.componentTableDepth(1)))
    extends Bundle {
  val providerId             = UInt(log2Ceil(tagComponentNum + 1).W)
  val altProviderId          = UInt(log2Ceil(tagComponentNum + 1).W)
  val isUseful               = Bool()
  val providerCtrBits        = Vec(tagComponentNum + 1, UInt(3.W))
  val tagPredictorQueryTags  = Vec(tagComponentNum, UInt(tagComponentTagWidth.W))
  val tagPredictorOriginTags = Vec(tagComponentNum, UInt(tagComponentTagWidth.W))
  val tagPredictorHitIndexs  = Vec(tagComponentNum, UInt(10.W))
  val tagPredictorUsefulBits = Vec(tagComponentNum, UInt(3.W))
  // global history Hash info
  val tageGhrInfo = new TageGhrInfo()
}

object TageMetaPort {
  def default = 0.U.asTypeOf(new TageMetaPort)
}
