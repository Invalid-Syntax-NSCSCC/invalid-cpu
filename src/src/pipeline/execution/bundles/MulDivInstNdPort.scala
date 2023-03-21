package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._

class MulDivInstNdPort extends Bundle {
  val op           = UInt(Param.Width.exeOp)
  val leftOperand  = UInt(Width.Reg.data)
  val rightOperand = UInt(Width.Reg.data)
}
