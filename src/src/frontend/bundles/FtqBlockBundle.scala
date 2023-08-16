package frontend.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import spec._
class FtqBlockBundle extends Bundle {
  val isValid          = Bool()
  val isCrossCacheline = Bool()
  val fetchLastIdx     = UInt(log2Ceil(Param.fetchInstMaxNum).W)
  val predictTaken     = Bool()
  val predictValid     = Bool()
  val startPc          = UInt(spec.Width.Mem.addr)
}

object FtqBlockBundle {
  def default = (new FtqBlockBundle).Lit(
    _.isValid -> false.B,
    _.isCrossCacheline -> false.B,
    _.fetchLastIdx -> 0.U(log2Ceil(Param.fetchInstMaxNum).W),
    _.predictTaken -> false.B,
    _.predictValid -> false.B,
    _.startPc -> 0.U(spec.Width.Mem.addr)
  )
}
