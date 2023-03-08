package common.bundles

import chisel3._

class PassThroughPort[PortT <: Data](signalFactory: PortT) extends Bundle {
  val in  = Input(signalFactory)
  val out = Output(signalFactory)
}
