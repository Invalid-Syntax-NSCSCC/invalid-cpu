package pipeline.common.bundles

import chisel3._
import chisel3.util.Valid
import spec.Param

class InstQueueEnqNdPort extends Bundle {
  val enqInfos = Vec(Param.fetchInstMaxNum, Valid(new FetchInstInfoBundle))
}

object InstQueueEnqNdPort {
  def default = 0.U.asTypeOf(new InstQueueEnqNdPort)
}
