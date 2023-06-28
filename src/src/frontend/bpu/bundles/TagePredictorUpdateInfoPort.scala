package frontend.bpu.bundles
import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import frontend.bpu.components.Bundles.TageMeta

class TagePredictorUpdateInfoPort extends Bundle {
  val valid          = Bool()
  val predictCorrect = Bool()
  val branchTaken    = Bool()
  val isConditional  = Bool()
  val bpuMeta        = new TageMeta
}

object TagePredictorUpdateInfoPort {
  def default = (new TagePredictorUpdateInfoPort).Lit(
    _.valid -> false.B,
    _.predictCorrect -> true.B,
    _.branchTaken -> true.B,
    _.isConditional -> true.B,
    _.bpuMeta -> TageMeta.default
  )
}
