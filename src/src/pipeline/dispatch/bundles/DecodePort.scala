package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._

class DecodePort extends Bundle {
  // The original instruction
  val instInfoPort = Input(new InstInfoBundle)

  // Output info
  val out = Output(new DecodeOutNdPort)
}
