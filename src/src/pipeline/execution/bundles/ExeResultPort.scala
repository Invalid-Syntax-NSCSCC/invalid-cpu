package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._
import pipeline.mem.bundles.MemRequestNdPort
import common.bundles.RfWriteNdPort
import chisel3.experimental.BundleLiterals._

class ExeResultPort extends Bundle {
  val memAccessPort = (new MemRequestNdPort)
  val gprWritePort  = (new RfWriteNdPort)
}

object ExeResultPort {
  val default = (new ExeResultPort).Lit(
    _.memAccessPort -> MemRequestNdPort.default,
    _.gprWritePort -> RfWriteNdPort.default
  )
}
