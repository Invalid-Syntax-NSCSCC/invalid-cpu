package frontend.bundles
import chisel3._

class ICacheRequestHandshakePort extends Bundle {
  val client  = Input(new ICacheRequestNdPort)
  val isReady = Output(Bool())
}
