package frontend.bundles

import chisel3._
import chisel3.util._
import spec.Param
class BackendCommitPort(
  val queueSize: Int = Param.BPU.ftqSize,
  val issueNum:  Int = Param.issueInstInfoMaxNum) {
  val ftqMetaUpdateValid       = Bool()
  val ftqMetaUpdateFtbDirty    = Bool()
  val ftqMetaUpdateJumpTarget  = UInt(spec.Width.Mem.addr)
  val ftqMetaUpdateFallThrough = UInt(spec.Width.Mem.addr)
  val ftqUpdateMetaId          = UInt(log2Ceil(queueSize).W)
  val commitBitMask            = UInt(issueNum.W)
  val commitBlockBitmask       = UInt(issueNum.W)
  val commitFtqId              = UInt(log2Ceil(issueNum).W)
  val commitMeta               = new BackendCommitMetaBundle
}
