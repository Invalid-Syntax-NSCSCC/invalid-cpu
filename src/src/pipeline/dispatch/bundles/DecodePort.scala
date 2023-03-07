package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._

class DecodePort extends Bundle {
  // The original instruction
  val inst = Input(UInt(Width.inst))

  // Output info
  val out = Output(new DecodeOutNdPort)

  // Other things need to be considered in the future
}
