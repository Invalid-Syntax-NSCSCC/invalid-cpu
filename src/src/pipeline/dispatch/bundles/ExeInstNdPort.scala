package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._

class ExeInstNdPort extends Bundle {
  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)

  // Operands
  val leftOperand  = UInt(Width.Reg.data)
  val rightOperand = UInt(Width.Reg.data)

  // Branch jump addr
  val pcAddr         = UInt(Width.Reg.data)
  val jumpBranchAddr = UInt(Width.Reg.data)
  def loadStoreImm   = jumpBranchAddr

  // GPR write (writeback)
  val gprWritePort = new RfAccessInfoNdPort

  // TODO: Signals in this port *might* not be sufficient
}

object ExeInstNdPort {
  def default = (new ExeInstNdPort).Lit(
    _.exeSel -> ExeInst.Sel.none,
    _.exeOp -> ExeInst.Op.nop,
    _.leftOperand -> 0.U,
    _.rightOperand -> 0.U,
    _.gprWritePort -> RfAccessInfoNdPort.default,
    _.pcAddr -> zeroWord,
    _.jumpBranchAddr -> zeroWord
  )
}
