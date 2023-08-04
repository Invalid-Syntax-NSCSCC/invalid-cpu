package frontend.bpu.bundles

import chisel3._
import chisel3.util._
import frontend.bpu.components.Bundles.TageMetaPort
import spec._

class BpuFtqMetaNdPort(
  ftbNway: Int = Param.BPU.FTB.nway)
    extends Bundle {
  val valid       = Bool()
  val ftbHit      = Bool()
  val ftbHitIndex = UInt(log2Ceil(ftbNway).W)
  val tageMeta    = new TageMetaPort
}

object BpuFtqMetaNdPort {
  def default = 0.U.asTypeOf(new BpuFtqMetaNdPort)
}
