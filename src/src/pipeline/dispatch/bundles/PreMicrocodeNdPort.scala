package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._

class PreMicrocodeNdPort extends Bundle {
  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)

  // GPR read (2)
  val gprReadPorts = Vec(2, new RfAccessInfoNdPort)

  // GPR write
  val gprWritePort = new RfAccessInfoNdPort

  // Immediate
  val isHasImm = Bool()
  val imm      = UInt(Width.Reg.data)

  // TODO: Signals in this port is not sufficient
}
