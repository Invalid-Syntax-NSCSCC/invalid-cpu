package pipeline.simple.pmu.bundles

import chisel3._

class PmuCacheNdPort extends Bundle {
  val newReq      = Bool()
  val cacheHit    = Bool()
  val cacheMiss   = Bool()
  val lineReplace = Bool()
}
