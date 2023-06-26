package pipeline.execution.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import common.bundles.RfWriteNdPort
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.memory.bundles.MemRequestNdPort

class ExeResultPort extends Bundle {
  val memAccessPort = (new MemRequestNdPort)
  val gprWritePort  = (new RfWriteNdPort)
  val instInfo      = new InstInfoNdPort
}

object ExeResultPort {
  def default = (new ExeResultPort).Lit(
    _.memAccessPort -> MemRequestNdPort.default,
    _.gprWritePort -> RfWriteNdPort.default,
    _.instInfo -> InstInfoNdPort.default
  )
}
