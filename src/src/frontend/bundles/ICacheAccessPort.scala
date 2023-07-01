package frontend.bundles

import chisel3._
import memory.bundles.MemResponseNdPort

class ICacheAccessPort extends Bundle {
  val req = new ICacheRequestHandshakePort
  val res = Output(new InstMemResponseNdPort)
}
