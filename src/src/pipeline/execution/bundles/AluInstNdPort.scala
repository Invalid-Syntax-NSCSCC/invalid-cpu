package pipeline.execution.bundles

import chisel3._
import spec._

class AluInstNdPort extends Bundle {
  val op             = UInt(Param.Width.exeOp)
  val leftOperand    = UInt(Width.Reg.data)
  val rightOperand   = UInt(Width.Reg.data)
  val jumpBranchAddr = UInt(Width.Reg.data)
}
