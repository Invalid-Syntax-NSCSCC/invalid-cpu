package frontend.bundles
import spec._
import chisel3._
import chisel3.util._
class FtqIFNdPort extends Bundle {
  val ftqBlockBundle = new FtqBlockBundle
  val redirect       = Bool()
  val ftqId          = UInt(Param.BPU.ftqPtrWitdh.W)
}

object FtqIFNdPort {
  def default = 0.U.asTypeOf(new FtqIFNdPort)
}
