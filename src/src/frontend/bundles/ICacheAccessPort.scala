package frontend.bundles

import chisel3._

class ICacheAccessPort extends Bundle {
  val req = new ICacheRequestHandshakePort
  val res = Output(new InstMemResponseNdPort)
}
