package memory.bundles

import chisel3._
import common.enums.ReadWriteSel
import spec._

class DCacheAccessPort extends Bundle {
  val req = new Bundle {
    val client  = Input(new MemAccessNdPort)
    val isReady = Output(Bool())
  }

  val res = new Bundle {
    val isComplete = Output(Bool())
    val read = new Bundle {
      val data = Output(UInt(Width.Mem.data))
    }
  }
}
