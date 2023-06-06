package frontend.bundles

import chisel3._
import chisel3.util._
import spec._
import pipeline.mem.enums.MemSizeType
import memory.bundles.MemResponseNdPort

class ICacheAccessPort extends Bundle {
  val req = new ICacheRequestHandshakePort
  val res = Output(new MemResponseNdPort)

}
