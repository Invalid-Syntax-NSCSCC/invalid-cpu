package pipeline.execution.bundles

import chisel3._
import spec._

class MulDivInstNdPort extends Bundle {
  val isSigned     = Bool()
  val leftOperand  = UInt(Width.Reg.data)
  val rightOperand = UInt(Width.Reg.data)
}
