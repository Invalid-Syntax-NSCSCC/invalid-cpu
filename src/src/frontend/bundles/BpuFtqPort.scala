package frontend.bundles

import chisel3._
import chisel3.util._
import frontend.bpu.bundles.BpuFtqMetaPort
import spec.Param
class BpuFtqPort {
  val ftqP0        = Input(new FtqBlockPort)
  val ftqP1        = Input(new FtqBlockPort)
  val ftqMeta      = Input(new BpuFtqMetaPort)
  val ftqFull      = Output(Bool())
  val ftqTrainMeta = Output(new FtqBpuMetaPort)
  val bpuP0        = Input(new FtqBlockPort)
  val bpuP1        = Input(new FtqBlockPort)
  val bpuMeta      = Input(new BpuFtqMetaPort)
  val bpuQueueFull = Output(Bool())
  val bpuTrainMeta = Output(new FtqBpuMetaPort)
  // pc redirect
  val mainBpuRedirectValid = Input(Bool())
}
