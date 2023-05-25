package pipeline.queue.bundles

import chisel3._
import pipeline.dispatch.bundles.InstInfoBundle

class DecodePort extends Bundle {
  // The original instruction
  val instInfoPort = Input(new InstInfoBundle)

  // Output info
  val out = Output(new DecodeOutNdPort)
}
