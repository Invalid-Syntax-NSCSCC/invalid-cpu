package frontend.bundles
import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import frontend.bpu.components.Bundles.TageMetaPort

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
  val isBranch       = Bool()
  val branchType     = UInt(2.W)
  val isTaken        = Bool()
  val predictedTaken = Bool()

  // FTB meta
  val startPc            = UInt(addr.W)
  val jumpTargetAddress  = UInt(addr.W)
  val fallThroughAddress = UInt(addr.W)
}

object FtqBpuMetaPort {
  def default = 0.U.asTypeOf(new FtqBpuMetaPort)
}
