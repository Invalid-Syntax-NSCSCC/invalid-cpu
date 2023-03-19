package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._

class AluResultNdPort extends Bundle {
  val logic      = UInt(Width.Reg.data)
  val shift      = UInt(Width.Reg.data)
  val arithmetic = UInt(Width.Reg.data)
}
