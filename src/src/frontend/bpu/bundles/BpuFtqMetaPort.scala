package frontend.bpu.bundles

import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import frontend.bpu.components.Bundles.TageMetaPort

class BpuFtqMetaPort(
  ftbNway: Int = Param.BPU.FTB.nway)
    extends Bundle {
  val valid       = Bool()
  val ftbHit      = Bool()
  val ftbHitIndex = UInt(log2Ceil(ftbNway).W)
  val bpuMeta     = new TageMetaPort
}

object BpuFtqMetaPort {
  def default = (new BpuFtqMetaPort).Lit(
    _.valid -> false.B,
    _.ftbHit -> false.B,
    _.ftbHitIndex -> 0.U(log2Ceil(Param.BPU.FTB.nway).W),
    _.bpuMeta -> TageMetaPort.default
  )
}
