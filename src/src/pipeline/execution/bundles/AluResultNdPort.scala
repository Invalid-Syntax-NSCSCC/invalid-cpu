package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._

class AluResultNdPort extends Bundle {
  val arithmetic = UInt(Width.Reg.data)
}
