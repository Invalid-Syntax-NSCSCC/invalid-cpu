package frontend.bundles

import chisel3._
import chisel3.util._
import spec.Param

class CuCommitFtqPort extends Bundle {
  val bitMask       = Input(Vec(Param.commitNum, Bool()))
  val blockBitmask  = Input(Vec(Param.commitNum, Bool()))
  val ftqId         = Input(UInt(Param.BPU.Width.id))
  val meta          = Input(new BackendCommitMetaBundle)
  val queryPcBundle = new QueryPcBundle
}
