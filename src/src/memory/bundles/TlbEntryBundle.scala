package memory.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import chisel3.util._
import spec._

class TlbEntryBundle extends Bundle {
  val compare = new TlbCompareEntryBundle
  val trans   = Vec(2, new TlbTransEntryBundle)
}

object TlbEntryBundle {
  def default = (new TlbEntryBundle).Lit(
    _.compare -> TlbCompareEntryBundle.default,
    _.trans -> Vec.Lit(TlbTransEntryBundle.default, TlbTransEntryBundle.default)
  )
}
