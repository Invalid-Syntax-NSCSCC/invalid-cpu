package pipeline.mem.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import common.enums.ReadWriteSel
import pipeline.mem.enums.MemSizeType
import spec._

class MemRequestNdPort extends Bundle {
  val isValid = Bool()
  val rw      = ReadWriteSel()
  val addr    = UInt(Width.Mem.addr)
  val mask    = UInt((Width.Mem._data / byteLength).W)

  val read = new Bundle {
    val isUnsigned = Bool()
    val size       = MemSizeType()
  }

  val write = new Bundle {
    val data = UInt(Width.Mem.data)
  }
}

object MemRequestNdPort {
  val default = (new MemRequestNdPort).Lit(
    _.isValid -> false.B,
    _.rw -> ReadWriteSel.read,
    _.addr -> zeroWord,
    _.mask -> zeroWord,
    _.read.isUnsigned -> false.B,
    _.read.size -> MemSizeType.word,
    _.write.data -> zeroWord
  )
}
