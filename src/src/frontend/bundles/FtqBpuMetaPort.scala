package frontend.bundles
import chisel3._
import chisel3.util._
import frontend.bpu.components.Bundles.TageMetaPort
import spec._

class BranchAddrBundle extends Bundle {
  val startPc            = UInt(spec.Width.Mem.addr)
  val jumpTargetAddress  = UInt(spec.Width.Mem.addr)
  val fallThroughAddress = UInt(spec.Width.Mem.addr)
}
class FtqBpuMetaPort(
  ftbNway: Int = Param.BPU.FTB.nway,
  addr:    Int = wordLength)
    extends Bundle {
  val valid            = Bool()
  val ftbHit           = Bool()
  val ftbHitIndex      = UInt(log2Ceil(ftbNway).W)
  val ftbDirty         = Bool()
  val isCrossCacheline = Bool()

  val tageMeta = new TageMetaPort

  // Backend Decode Info
  val branchTakenMeta = new BranchTakenMetaBundle

  // FTB train meta
  val branchAddrBundle   = new BranchAddrBundle
}

object FtqBpuMetaPort {
  def default = 0.U.asTypeOf(new FtqBpuMetaPort)
}
