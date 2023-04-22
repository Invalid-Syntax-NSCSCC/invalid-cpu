package memory.bundles

import chisel3._
import common.enums.ReadWriteSel
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class MemRequestNdPort extends Bundle {
  val isValid    = Bool()
  val rw         = ReadWriteSel()
  val addr       = UInt(Width.Mem.addr)
  val isUnsigned = Bool()

  val write = new Bundle {
    val data = UInt(Width.Mem.data)
    val mask = UInt((Width.Mem._data / byteLength).W)
  }
}

object MemRequestNdPort {
  val default = (new MemRequestNdPort).Lit(
    _.isValid -> false.B,
    _.rw -> ReadWriteSel.read,
    _.addr -> zeroWord,
    _.isUnsigned -> false.B,
    _.write.data -> zeroWord,
    _.write.mask -> zeroWord
  )
}
