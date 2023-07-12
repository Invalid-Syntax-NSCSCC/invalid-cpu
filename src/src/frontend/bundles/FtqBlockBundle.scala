package frontend.bundles

import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
class FtqBlockBundle extends Bundle {
  val isValid          = Bool()
  val isCrossCacheline = Bool()
  val length           = UInt(log2Ceil(Param.fetchInstMaxNum + 1).W)
  val predictTaken     = Bool()
  val predictValid     = Bool()
  val startPc          = UInt(spec.Width.Mem.addr)
}

object FtqBlockBundle {
  def default = (new FtqBlockBundle).Lit(
    _.isValid -> false.B,
    _.isCrossCacheline -> false.B,
    _.length -> 0.U(log2Ceil(Param.fetchInstMaxNum + 1).W),
    _.predictTaken -> false.B,
    _.predictValid -> false.B,
    _.startPc -> 0.U(spec.Width.Mem.addr)
  )
}
