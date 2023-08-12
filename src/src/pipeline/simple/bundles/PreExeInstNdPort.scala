package pipeline.simple.bundles

import chisel3._
import common.bundles.RfAccessInfoNdPort
import spec._
import spec.ExeInst.OpBundle

class PreExeInstNdPort(readNum: Int = Param.instRegReadNum) extends Bundle {
  // Micro-instruction for execution stage
  val exeOp = new OpBundle

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

  def loadStoreImm      = jumpBranchAddr
  def csrAddr           = jumpBranchAddr
  def code              = jumpBranchAddr
  def tlbInvalidateInst = jumpBranchAddr

  val needRefetch = Bool()
  val isTlb       = Bool()

  val isIssueMainPipeline = Bool()
  val isPrivilege         = Bool()
  val isBranch            = Bool()
  val branchType          = UInt(Param.BPU.BranchType.width.W)

  val forbidOutOfOrder = Bool()
}

object PreExeInstNdPort {
  def default = 0.U.asTypeOf(new PreExeInstNdPort)
}
