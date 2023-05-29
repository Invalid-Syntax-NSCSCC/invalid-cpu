package memory.bundles

import chisel3._
import common.enums.ReadWriteSel
import spec._

class ICacheAccessPort extends Bundle {
  val isValid = Input(Bool())
  val isReady = Output(Bool())
  // val rw      = Input(ReadWriteSel())
  val addr = Input(UInt(Width.Mem.addr))

  val read = new Bundle {
    val isValid = Output(Bool())
    val data    = Output(UInt(Width.Mem.data))
  }

  // TODO: Ports are not sufficient
}
