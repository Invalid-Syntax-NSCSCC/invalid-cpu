package pipeline.dispatch.bundles

import chisel3._
import common.bundles.RfAccessInfoNdPort
import spec._

class DecodeOutNdPort extends Bundle {
  // Is instruction matched
  val isMatched = Bool()

  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)

  // GPR read (2)
  val regFileReadPorts = Vec(2, new RfAccessInfoNdPort)

  // GPR write
  val regFileWritePort = new RfAccessInfoNdPort

  // Immediate
  val isHasImm = Bool()
  val imm = UInt(Width.Reg.data)

  // Other things need to be considered in the future
}
