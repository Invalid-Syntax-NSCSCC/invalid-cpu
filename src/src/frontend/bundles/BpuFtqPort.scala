package frontend.bundles

import chisel3._
import frontend.bpu.bundles.BpuFtqMetaPort
class BpuFtqPort extends Bundle {
  val ftqP0        = Input(new FtqBlockPort)
  val ftqP1        = Input(new FtqBlockPort)
  val ftqMeta      = Input(new BpuFtqMetaPort)
  val ftqFull      = Output(Bool())
  val ftqTrainMeta = Output(new FtqBpuMetaPort)
  // pc redirect
  val mainBpuRedirectValid = Input(Bool())
}
