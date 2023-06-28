package frontend.bundles

import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
class FtqBlockPort(
  fetchWidth: Int = 4)
    extends Bundle {
  val valid            = Output(Bool())
  val isCrossCacheline = Output(Bool())
  val length           = Output(UInt(log2Ceil(fetchWidth + 1).W))
  val predictTaken     = Output(Bool())
  val predictValid     = Output(Bool())
  val startPc          = Output(UInt(spec.Width.Mem.addr))
}

object FtqBlockPort {
  val fetchWidth = 4
  def default = (new FtqBlockPort).Lit(
    _.valid -> false.B,
    _.isCrossCacheline -> false.B,
    _.length -> 0.U(log2Ceil(fetchWidth + 1).W),
    _.predictTaken -> true.B,
    _.predictValid -> false.B,
    _.startPc -> 0.U(spec.Width.Mem.addr)
  )
}
