package pipeline.rob.lvt.bundles

import chisel3._
import chisel3.util._
import spec._

class LvtReadPort[T <: Data](
  addrWidth:   Int,
  elemFactory: => T)
    extends Bundle {
  val addr = Input(UInt(addrWidth.W))
  val data = Output(elemFactory)
}
