package frontend.bundles

import chisel3._
import chisel3.util._
import spec.Param

class CuCommitFtqNdPort extends Bundle {
  val bitMask      = Vec(Param.commitNum, Bool())
  val blockBitmask = Vec(Param.commitNum, Bool())
  val ftqId        = UInt(Param.BPU.Width.id)
  val meta         = new BackendCommitMetaBundle
}

object CuCommitFtqNdPort {
  def default = 0.U.asTypeOf(new CuCommitFtqNdPort)
}
