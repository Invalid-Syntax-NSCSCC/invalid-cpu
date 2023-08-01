package pmu.bundles

import chisel3._
import chisel3.util._
import spec._

class PmuCacheNdPort extends Bundle {
  val newReq      = Bool()
  val cacheHit    = Bool()
  val cacheMiss   = Bool()
  val lineReplace = Bool()
}
