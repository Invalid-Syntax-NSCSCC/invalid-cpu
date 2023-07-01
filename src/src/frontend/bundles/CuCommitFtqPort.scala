package frontend.bundles

import chisel3._
import chisel3.util._
import spec.Param

class CuCommitFtqPort extends Bundle {
  val bitMask       = Input(UInt(Param.issueInstInfoMaxNum.W))
  val blockBitmask  = Input(UInt(Param.issueInstInfoMaxNum.W))
  val ftqId         = Input(UInt(Param.BPU.Width.id))
  val meta          = Input(new BackendCommitMetaBundle)
  val queryPcBundle = new QueryPcBundle
}
