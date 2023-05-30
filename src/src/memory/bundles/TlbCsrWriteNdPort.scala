package memory.bundles

import chisel3._
import chisel3.util._
import control.csrRegsBundles.{TlbehiBundle, TlbeloBundle, TlbidxBundle}
import spec._

class TlbCsrWriteNdPort extends Bundle {
  val tlbidx    = Valid(new TlbidxBundle)
  val tlbehi    = Valid(new TlbehiBundle)
  val tlbeloVec = Vec(2, Valid(new TlbeloBundle))
}
