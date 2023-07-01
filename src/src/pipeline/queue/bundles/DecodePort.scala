package pipeline.queue.bundles

import chisel3._
import pipeline.dispatch.bundles.FetchInstInfoBundle

class DecodePort extends Bundle {
  // The original instruction
  val instInfoPort = Input(new FetchInstInfoBundle)

  // Output info
  val out = Output(new DecodeOutNdPort)
}
