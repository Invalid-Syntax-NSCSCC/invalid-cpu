package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._

abstract class DecodePort extends Bundle {
  // The original instruction
  val inst = Input(UInt(Width.inst))

  // Is instruction matched
  val isMatched = Output(Bool())

  // Micro-instruction for execution stage
  val exeSel = Output(UInt(Param.Width.exeSel))
  val exeOp  = Output(UInt(Param.Width.exeOp))

  // GPR read (2)
  val regFileReadPorts = Output(Vec(2, new RfAccessInfoNdPort))

  // GPR write
  val regFileWritePort = Output(new RfAccessInfoNdPort)

  // Other things need to be considered in the future
}
