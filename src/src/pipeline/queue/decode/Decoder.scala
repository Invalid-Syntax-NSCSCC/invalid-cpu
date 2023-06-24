package pipeline.queue.decode

import chisel3._
import pipeline.queue.bundles.DecodePort

abstract class Decoder extends Module {
  // A decoder should:
  // 1) Extract and extend immediate from instruction, if it has
  // 2) Extract register information from instruction
  // 3) Something else...

  val io = IO(new DecodePort)
}
