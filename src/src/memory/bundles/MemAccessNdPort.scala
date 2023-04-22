package memory.bundles

import chisel3._
import common.enums.ReadWriteSel
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class MemAccessNdPort extends Bundle {
  val isValid    = Bool()
  val rw         = ReadWriteSel()
  val addr       = UInt(Width.Reg.data)
  val isUnsigned = Bool()

  val write = new Bundle {
    val data = UInt(Width.Mem.data)
    val mask = UInt((Width.Mem._data / byteLength).W)
  }
}

object MemAccessNdPort {
  val default = (new MemAccessNdPort).Lit(
    _.isValid -> false.B,
    _.rw -> ReadWriteSel.read,
    _.addr -> zeroWord,
    _.write.data -> zeroWord,
    _.write.mask -> zeroWord
  )
}
