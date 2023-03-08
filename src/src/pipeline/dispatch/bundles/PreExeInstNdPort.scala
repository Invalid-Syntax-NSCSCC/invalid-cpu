package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.experimental.VecLiterals._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._

class PreExeInstNdPort(readNum: Int = Param.instRegReadNum) extends Bundle {
  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)

  // GPR read (`readNum`)
  val gprReadPorts = Vec(readNum, new RfAccessInfoNdPort)

  // GPR write
  val gprWritePort = new RfAccessInfoNdPort

  // Immediate
  val isHasImm = Bool()
  val imm      = UInt(Width.Reg.data)

  // TODO: Signals in this port is not sufficient
}

object PreExeInstNdPort {
  def default = (new PreExeInstNdPort).Lit(
    _.exeSel -> ExeInst.Sel.none,
    _.exeOp -> ExeInst.Op.nop,
    _.gprReadPorts -> Vec.Lit(RfAccessInfoNdPort.default, RfAccessInfoNdPort.default),
    _.gprWritePort -> RfAccessInfoNdPort.default,
    _.isHasImm -> false.B,
    _.imm -> 0.U
  )
}
