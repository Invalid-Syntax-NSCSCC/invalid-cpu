package pipeline.memory.bundles

import chisel3._
import spec._

class StoreInfoBundle extends Bundle {
  val addr     = UInt(Width.Mem.addr)
  val mask     = UInt((Width.Mem._data / byteLength).W)
  val data     = UInt(Width.Mem.data)
  val isCached = Bool()
}
