package pipeline.simple.decode

import chisel3._
import pipeline.simple.decode.bundles.DecodePort

abstract class BaseDecoder extends Module {
  // A decoder should:
  // 1) Extract and extend immediate from instruction, if it has
  // 2) Extract register information from instruction
  // 3) Something else...

  val io = IO(new DecodePort)
}
