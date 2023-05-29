package frontend.bundles

import chisel3._
import chisel3.util._
import spec._
import pipeline.mem.enums.MemSizeType

class ICacheAccessPort extends Bundle {
  val req = new Bundle {
    val client = Input(new Bundle {
      val isValid = Bool()
      val addr    = UInt(Width.Mem.addr)
    })
    val isReady = Output(Bool())
  }

  val res = Output(new Bundle {
    val isComplete = Bool()
    val read = new Bundle {
      val data = UInt(Width.Mem.data)
    }
  })

}
