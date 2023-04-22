package memory.bundles

import chisel3._
import common.enums.ReadWriteSel
import spec._

class MemAccessPort extends Bundle {
  val req = new Bundle {
    val client  = Input(new MemRequestNdPort)
    val isReady = Output(Bool())
  }

  val res = Output(new MemResponseNdPort)
}
