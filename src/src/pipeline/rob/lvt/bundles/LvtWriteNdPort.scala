package pipeline.rob.lvt.bundles

import chisel3._
import chisel3.util._
import spec._

class LvtWriteNdPort[T <: Data](
  addrWidth:   Int,
  elemFactory: => T)
    extends Bundle {
  val en   = Bool()
  val addr = UInt(addrWidth.W)
  val data = elemFactory
}
