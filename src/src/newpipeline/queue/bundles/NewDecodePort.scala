package newpipeline.queue.bundles

import chisel3._
import pipeline.dispatch.bundles.FetchInstInfoBundle
import newpipeline.queue.bundles.NewDecodeOutNdPort

class NewDecodePort extends Bundle {
  // The original instruction
  val instInfoPort = Input(new FetchInstInfoBundle)

  // Output info
  val out = Output(new NewDecodeOutNdPort)
}
