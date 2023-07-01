package frontend.bundles

import chisel3._
import chisel3.util._
import spec.Param
class BackendCommitPort(
  val queueSize: Int = Param.BPU.ftqSize,
  val issueNum:  Int = Param.issueInstInfoMaxNum)
    extends Bundle {
  val exCommitFtqNdBundle = new ExCommitFtqNdPort
  val cuCommitFtqNdBundle = new CuCommitFtqNdPort(issueNum)
}

class ExCommitFtqNdPort extends Bundle {
  val ftqMetaUpdateValid       = Bool()
  val ftqMetaUpdateFtbDirty    = Bool()
  val ftqMetaUpdateJumpTarget  = UInt(spec.Width.Mem.addr)
  val ftqMetaUpdateFallThrough = UInt(spec.Width.Mem.addr)
  val ftqUpdateMetaId          = UInt(Param.BPU.Width.id)
}

class CuCommitFtqNdPort(val issueNum: Int = Param.issueInstInfoMaxNum) extends Bundle {
  val commitBitMask      = UInt(issueNum.W)
  val commitBlockBitmask = UInt(issueNum.W)
  val commitFtqId        = UInt(Param.BPU.Width.id)
  val commitMeta         = new BackendCommitMetaBundle
}
