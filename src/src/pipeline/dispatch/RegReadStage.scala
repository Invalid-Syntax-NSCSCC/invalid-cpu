package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.IssuedInfoNdPort
import spec._

class RegReadStage extends Module {
  val io = IO(new Bundle {
    val issuedInfoPort = Input(new IssuedInfoNdPort)
  })
}
