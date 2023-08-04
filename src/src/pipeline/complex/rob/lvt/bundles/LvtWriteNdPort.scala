package pipeline.complex.rob.lvt.bundles

import chisel3._

class LvtWriteNdPort[T <: Data](
  addrWidth:   Int,
  elemFactory: => T)
    extends Bundle {
  val en   = Bool()
  val addr = UInt(addrWidth.W)
  val data = elemFactory
}
