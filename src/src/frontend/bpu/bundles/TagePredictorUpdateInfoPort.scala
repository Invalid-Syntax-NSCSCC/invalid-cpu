package frontend.bpu.bundles
import chisel3._
import frontend.bpu.components.Bundles.{TageGhrInfo, TageMetaPort}

class TagePredictorUpdateInfoPort extends Bundle {
  val valid          = Bool()
  val predictCorrect = Bool()
  val branchTaken    = Bool()
  val isConditional  = Bool()
  val tageOriginMeta = new TageMetaPort
}

object TagePredictorUpdateInfoPort {
  def default = 0.U.asTypeOf(new TagePredictorUpdateInfoPort)
}
