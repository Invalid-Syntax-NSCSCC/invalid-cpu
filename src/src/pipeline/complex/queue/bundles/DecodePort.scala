package pipeline.complex.queue.bundles

import chisel3._
import pipeline.common.bundles.FetchInstInfoBundle

class DecodePort extends Bundle {
  // The original instruction
  val instInfoPort = Input(new FetchInstInfoBundle)

  // Output info
  val out = Output(new DecodeOutNdPort)
}
