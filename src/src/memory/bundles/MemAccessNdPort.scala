package memory.bundles

import chisel3._
import common.enums.ReadWriteSel
import spec._

class MemAccessNdPort extends Bundle {
  val isValid = Bool()
  val rw      = ReadWriteSel()
  val addr    = UInt(Width.Reg.data)

  val write = new Bundle {
    val data = UInt(Width.Reg.data)
    val mask = UInt(Width.Reg.data)
  }
}
