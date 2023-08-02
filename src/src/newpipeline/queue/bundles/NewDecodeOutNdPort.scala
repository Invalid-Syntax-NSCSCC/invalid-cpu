package newpipeline.queue.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import newpipeline.queue.bundles.NewPreExeInstNdPort

class NewDecodeOutNdPort extends Bundle {
  // Is instruction matched
  val isMatched = Bool()

  val info = new NewPreExeInstNdPort
}

object NewDecodeOutNdPort {
  def default = 0.U.asTypeOf(new NewDecodeOutNdPort)
}
