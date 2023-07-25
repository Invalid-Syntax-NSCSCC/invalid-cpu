package frontend.bundles

import chisel3._
import spec.Param.Count
import spec._

class InstMemResponseNdPort extends Bundle {
  val isComplete = Bool()
  val isFailed   = Bool()
  val read = new Bundle {
    val dataVec = Vec(Param.Count.ICache.dataPerLine, UInt(Width.Mem.data))
  }
}
