package frontend.bpu.bundles
import spec._
import chisel3._
import chisel3.util._
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
  def default = 0.U.asTypeOf(new TagePredictorUpdateInfoPort)
}
