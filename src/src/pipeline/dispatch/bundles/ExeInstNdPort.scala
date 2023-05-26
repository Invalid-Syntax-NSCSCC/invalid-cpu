package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import common.bundles.RfAccessInfoNdPort
import spec._
import pipeline.writeback.bundles.InstInfoNdPort

class ExeInstNdPort extends Bundle {
  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)
  // Operands
  val leftOperand  = UInt(Width.Reg.data)
  val rightOperand = UInt(Width.Reg.data)

  // Branch jump addr
  val jumpBranchAddr = UInt(Width.Reg.data)
  def loadStoreImm   = jumpBranchAddr
  def csrData        = jumpBranchAddr

  // GPR write (writeback)
  val gprWritePort = new RfAccessInfoNdPort

  val instInfo = new InstInfoNdPort
}

object ExeInstNdPort {
  def default = (new ExeInstNdPort).Lit(
    _.exeSel -> ExeInst.Sel.none,
    _.exeOp -> ExeInst.Op.nop,
    _.leftOperand -> 0.U,
    _.rightOperand -> 0.U,
    _.gprWritePort -> RfAccessInfoNdPort.default,
    _.jumpBranchAddr -> zeroWord,
    _.instInfo -> InstInfoNdPort.default
  )
}
