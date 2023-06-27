package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import common.bundles.RfAccessInfoNdPort
import memory.bundles.TlbMaintenanceNdPort
import spec._

class PreExeInstNdPort(readNum: Int = Param.instRegReadNum) extends Bundle {
  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)

  // GPR read (`readNum`)
  val gprReadPorts = Vec(readNum, new RfAccessInfoNdPort)
  val csrReadEn    = Bool()
  val csrWriteEn   = Bool()

  // GPR write
  val gprWritePort = new RfAccessInfoNdPort

  // Immediate
  val isHasImm = Bool()
  val imm      = UInt(Width.Reg.data)

  // Branch jump addr
  val jumpBranchAddr = UInt(Width.Reg.data)

  def loadStoreImm = jumpBranchAddr

  def csrAddr = jumpBranchAddr

  def code = jumpBranchAddr

  def tlbInvalidateInst = jumpBranchAddr

  val needCsr = Bool()
  val isTlb   = Bool()
  // TODO: Signals in this port is not sufficient
}

object PreExeInstNdPort {
  def default = (new PreExeInstNdPort).Lit(
    _.needCsr -> false.B,
    _.exeSel -> ExeInst.Sel.none,
    _.exeOp -> ExeInst.Op.nop,
    _.gprReadPorts -> Vec.Lit(RfAccessInfoNdPort.default, RfAccessInfoNdPort.default),
    _.gprWritePort -> RfAccessInfoNdPort.default,
    _.isHasImm -> false.B,
    _.imm -> 0.U,
    _.jumpBranchAddr -> zeroWord,
    _.csrReadEn -> false.B,
    _.csrWriteEn -> false.B,
    _.isTlb -> false.B
  )
}
