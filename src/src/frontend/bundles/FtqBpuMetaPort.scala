package frontend.bundles
import spec._
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class FtqBpuMetaPort(
  ftbNway: Int = Param.BPU.FTB.nway,
  addr:    Int = wordLength)
    extends Bundle {
  val valid            = Bool()
  val ftbHit           = Bool()
  val ftbHitIndex      = UInt(log2Ceil(ftbNway).W)
  val ftbDirty         = Bool()
  val isCrossCacheline = Bool()

  val bpuMeta = new TageMetaPort

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
  def default = (new FtqBpuMetaPort).Lit(
    _.valid -> false.B,
    _.ftbHit -> false.B,
    _.ftbHitIndex -> 0.U(log2Ceil(Param.BPU.FTB.nway).W),
    _.ftbDirty -> false.B,
    _.isCrossCacheline -> false.B,
    _.bpuMeta -> TageMetaPort.default,
    _.isBranch -> false.B,
    _.branchType -> 0.U(2.W),
    _.isTaken -> true.B,
    _.predictedTaken -> true.B,
    _.startPc -> 0.U(Width.Mem.addr),
    _.jumpTargetAddress -> 0.U(Width.Mem.addr),
    _.fallThroughAddress -> 0.U(Width.Mem.addr)
  )
}
