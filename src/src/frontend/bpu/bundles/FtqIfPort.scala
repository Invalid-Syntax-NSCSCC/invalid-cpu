package frontend.bpu.bundles
import spec._
import chisel3._
import chisel3.util._
import frontend.bundles.FtqBlockPort
class FtqIfPort extends Bundle {
  val ftqBlock            = Input(new FtqBlockPort)
  val ifuFrontendRedirect = Input(Bool())
  val ifuFtqId            = Input(UInt(log2Ceil(spec.Param.BPU.ftqSize).W))
  val ifAccept           = Output(Bool()) // Must return in the same cycle
}
