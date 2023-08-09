package frontend.bundles
import chisel3._
import chisel3.util._
import frontend.bpu.components.Bundles.{TageGhrInfo, TageMetaPort}
import spec._

class BranchAddrBundle extends Bundle {
  val startPc         = UInt(spec.Width.Mem.addr)
  val jumpTargetAddr  = UInt(spec.Width.Mem.addr)
  val fallThroughAddr = UInt(spec.Width.Mem.addr)
}

class ExeFtqFixGhrBundle extends Bundle {
  val isExeFixErrorGhr = Bool()
  val isExeUpdateGhr   = Bool()
  val exeFixIsTaken    = Bool()
}
class GhrUpdateSignalBundle extends Bundle {
  val isCommitFixGhr          = Bool()
  val exeFixBundle            = new ExeFtqFixGhrBundle
  val isPredecoderFixGhr      = Bool()
  val isPredecoderBranchTaken = Bool()
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

  val tageOriginMeta = new TageMetaPort

  // Backend Decode Info
  val branchTakenMeta = new BranchTakenMetaBundle

  // FTB train meta
  val branchAddrBundle = new BranchAddrBundle

  // train meta
  val ghrUpdateSignalBundle = new GhrUpdateSignalBundle // global history register
  val tageGhrInfo           = new TageGhrInfo()
}

object FtqBpuMetaPort {
  def default = 0.U.asTypeOf(new FtqBpuMetaPort)
}
