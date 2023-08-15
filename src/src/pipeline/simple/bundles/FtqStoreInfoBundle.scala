package pipeline.simple.bundles

import chisel3._
import spec._

class FtqStoreInfoBundle extends Bundle {
  val isLastInBlock = Bool()
  val ftqId         = UInt(Param.BPU.Width.id)
}
