package pipeline.mem.bundles

import chisel3._
import chisel3.util._
import pipeline.mem.enums.ReadWriteSel
import spec._

class DCacheAccessPort extends Bundle {
  val isValid = Input(Bool())
  val isReady = Output(Bool())
  val rw      = Input(ReadWriteSel())
  val addr    = Input(UInt(Width.Mem.addr))

  val read = new Bundle {
    val isValid = Input(Bool())
    val data    = Output(UInt(Width.Mem.data))
  }
  val write = new Bundle {
    val isComplete = Output(Bool())
    val data       = Input(UInt(Width.Mem.data))
  }

  // TODO: Ports are not sufficient
}
