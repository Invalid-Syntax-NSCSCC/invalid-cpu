package frontend.bundles
import spec._
import chisel3._
import chisel3.util._
class FtqIFPort extends Bundle {
  val ftqBlockBundle = Input(new FtqBlockBundle)
  val redirect       = Input(Bool())
  val ftqId          = Input(UInt(Param.BPU.ftqPtrWitdh.W))
  val ready          = Output(Bool()) // Ready signal Must return in the same cycle
}
