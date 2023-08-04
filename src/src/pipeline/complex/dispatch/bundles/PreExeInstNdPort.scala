package pipeline.complex.dispatch.bundles

import chisel3._
import common.bundles.RfAccessInfoNdPort
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

  val needRefetch = Bool()
  val isTlb       = Bool()

  val issueEn          = Vec(Param.pipelineNum, Bool())
  val forbidOutOfOrder = Bool()
  val isPrivilege      = Bool()
  val isBranch         = Bool()
  val branchType       = UInt(Param.BPU.BranchType.width.W)
  // TODO: Signals in this port is not sufficient
}

object PreExeInstNdPort {
  def default = 0.U.asTypeOf(new PreExeInstNdPort)
}
