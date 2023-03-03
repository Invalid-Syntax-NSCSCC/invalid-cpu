package pipeline.dispatch.decode

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.ImmRjRdDecodePort
import spec._

class Decoder_2RI12 extends Decoder {
  val io = IO(new ImmRjRdDecodePort)

  // TODO: Add decoding process for ADDI.W as the first baby step
}
