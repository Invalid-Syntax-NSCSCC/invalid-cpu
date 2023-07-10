package frontend.bpu.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import frontend.bpu.components.Bundles.TageMetaPort
import spec._

class FtqBpuMetaEntry(
  addr:    Int = spec.wordLength,
  ftbNway: Int = Param.BPU.FTB.nway)
    extends Bundle {
  val valid       = Bool()
  val ftbHit      = Bool()
  val ftbHitIndex = UInt(log2Ceil(ftbNway).W)
  val bpuMeta     = new TageMetaPort
}

object FtqBpuMetaEntry {
  def default = (new FtqBpuMetaEntry).Lit(
    _.valid -> false.B,
    _.ftbHit -> false.B,
    _.ftbHitIndex -> 0.U(log2Ceil(Param.BPU.FTB.nway).W),
    _.bpuMeta -> TageMetaPort.default
  )
}
