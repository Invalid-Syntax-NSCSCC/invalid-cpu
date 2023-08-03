package frontend.bundles
import chisel3._
import spec._
class FtqIFNdPort extends Bundle {
  val ftqBlockBundle = new FtqBlockBundle
  val redirect       = Bool()
  val ftqId          = UInt(Param.BPU.ftqPtrWidth.W)
}

object FtqIFNdPort {
  def default = 0.U.asTypeOf(new FtqIFNdPort)
}
