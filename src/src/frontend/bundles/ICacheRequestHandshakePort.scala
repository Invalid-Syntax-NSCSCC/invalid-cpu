package frontend.bundles
import chisel3._
import chisel3.util._
import frontend.bundles.ICacheRequestNdPort
import spec._

class ICacheRequestHandshakePort extends Bundle {
  val client  = Input(new ICacheRequestNdPort)
  val isReady = Output(Bool())
}
