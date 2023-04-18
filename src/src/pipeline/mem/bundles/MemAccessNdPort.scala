package pipeline.mem.bundles

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import common.enums.ReadWriteSel

class MemAccessNdPort extends Bundle {
  val isValid  = Bool()
  val isAtomic = Bool()
  val rw       = ReadWriteSel
  val addr     = UInt(Width.Reg.data)
  val write = new Bundle {
    val mask = UInt(Width.Reg.data)
    val data = UInt(Width.Reg.data)
  }
}
