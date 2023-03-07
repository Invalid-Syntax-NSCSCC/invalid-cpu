package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import spec._

class MicrocodeNdPort extends Bundle {
  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)

  // Operands
  val leftOperand  = UInt(Width.Reg.data)
  val rightOperand = UInt(Width.Reg.data)

  // TODO: Signals in this port *might* not be sufficient
}
