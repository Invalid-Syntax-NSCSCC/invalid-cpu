package frontend.bundles

import chisel3._
import chisel3.util._
import spec.Param

class CuCommitFtqNdPort extends Bundle {
  val BitMask       = Input(UInt(Param.issueInstInfoMaxNum.W))
  val BlockBitmask  = Input(UInt(Param.issueInstInfoMaxNum.W))
  val FtqId         = Input(UInt(Param.BPU.Width.id))
  val Meta          = Input(new BackendCommitMetaBundle)
  val queryPcBundle = new QueryPcBundle
}
