package frontend.bundles

import chisel3._
import chisel3.util._
import frontend.bpu.bundles.BpuFtqMetaPort
import spec.Param
class BpuFtqPort extends Bundle {
  val ftqP0        = Input(new FtqBlockBundle)
  val ftqP1        = Input(new FtqBlockBundle)
  val ftqMeta      = Input(new BpuFtqMetaPort)
  val ftqFull      = Output(Bool())
  val ftqTrainMeta = Output(new FtqBpuMetaPort)
  // pc redirect
  val mainBpuRedirectValid = Input(Bool())
}
