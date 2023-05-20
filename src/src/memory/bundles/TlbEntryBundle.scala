package memory.bundles

import chisel3._
import chisel3.util._
import spec._

class TlbEntryBundle extends Bundle {
  val compare = new TlbCompareEntryBundle
  val trans   = Vec(2, new TlbTransEntryBundle)
}
