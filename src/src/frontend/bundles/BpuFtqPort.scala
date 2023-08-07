package frontend.bundles

import chisel3._
import frontend.bpu.bundles.BpuFtqMetaNdPort
class BpuFtqPort extends Bundle {
  val ftqP0           = Input(new FtqBlockBundle)
  val ftqP1           = Input(new FtqBlockBundle)
  val bpuQueryMeta    = Input(new BpuFtqMetaNdPort)
  val ftqFull         = Output(Bool())
  val ftqBpuTrainMeta = Output(new FtqBpuMetaPort)
  // pc redirect
  val bpuRedirectValid = Input(Bool())
}
