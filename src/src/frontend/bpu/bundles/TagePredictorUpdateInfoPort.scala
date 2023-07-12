package frontend.bpu.bundles
import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import frontend.bpu.components.Bundles.TageMetaPort

class TagePredictorUpdateInfoPort extends Bundle {
  val valid          = Bool()
  val predictCorrect = Bool()
  val branchTaken    = Bool()
  val isConditional  = Bool()
  val bpuMeta        = new TageMetaPort
}

object TagePredictorUpdateInfoPort {
  def default = (new TagePredictorUpdateInfoPort).Lit(
    _.valid -> false.B,
    _.predictCorrect -> true.B,
    _.branchTaken -> true.B,
    _.isConditional -> true.B,
    _.bpuMeta -> TageMetaPort.default
  )
}
