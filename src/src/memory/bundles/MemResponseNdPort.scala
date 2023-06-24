package memory.bundles

import chisel3._
import spec._

class MemResponseNdPort extends Bundle {
  val isComplete = Bool()
  val isFailed   = Bool()
  val read = new Bundle {
    val data = UInt(Width.Mem.data)
  }
}
