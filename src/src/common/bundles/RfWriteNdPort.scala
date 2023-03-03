package common.bundles

import chisel3._
import spec._

class RfWriteNdPort extends Bundle {
  val en   = Bool()
  val addr = UInt(Width.Reg.addr)
  val data = UInt(Width.Reg.data)
}
