package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._

class ImmRjRdDecodePort extends DecodePort {
  // Extended immediate
  val imm = Output(UInt(Width.Reg.data))

  // Other things need to be considered in the future
}
