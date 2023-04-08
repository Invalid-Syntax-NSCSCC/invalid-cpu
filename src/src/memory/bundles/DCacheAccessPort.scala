package memory.bundles

import chisel3._
import memory.enums.ReadWriteSel
import spec._

class DCacheAccessPort extends Bundle {
  val isValid = Input(Bool())
  val isReady = Output(Bool())
  val rw      = Input(ReadWriteSel())
  val addr    = Input(UInt(Width.Mem.addr))

  val read = new Bundle {
    val isValid = Output(Bool())
    val data    = Output(UInt(Width.Mem.data))
  }
  val write = new Bundle {
    val isComplete = Output(Bool())
    val data       = Input(UInt(Width.Mem.data))
    val mask       = Input(UInt(Width.Mem.data))
  }

  // TODO: Ports are not sufficient
}
