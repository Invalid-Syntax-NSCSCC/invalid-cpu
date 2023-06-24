package pipeline.dispatch

import chisel3._
import chisel3.experimental.BundleLiterals._
import common.bundles.RfReadPort
import control.bundles.CsrReadPort
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.BaseStage
import pipeline.dispatch.bundles.PreExeInstNdPort
import pipeline.execution.ExeNdPort
import spec._

class RegReadNdPort extends Bundle {
  val preExeInstInfo = new PreExeInstNdPort
  val instInfo       = new InstInfoNdPort
}

object RegReadNdPort {
  def default = (new RegReadNdPort).Lit(
    _.preExeInstInfo -> PreExeInstNdPort.default,
    _.instInfo -> InstInfoNdPort.default
  )
}

class RegReadPeerPort(readNum: Int, csrReadNum: Int) extends Bundle {
  // `RegReadStage` <-> `Regfile`
  val gprReadPorts = Vec(readNum, Flipped(new RfReadPort))

  // `RegReadStage <-> `Csr`
  val csrReadPorts = Vec(csrReadNum, Flipped(new CsrReadPort))

}

class RegReadStage(readNum: Int = Param.instRegReadNum, csrReadNum: Int = Param.csrReadNum)
    extends BaseStage(
      new RegReadNdPort,
      new ExeNdPort,
      RegReadNdPort.default,
      Some(new RegReadPeerPort(readNum, csrReadNum))
    ) {

  require(csrReadNum == 1)

  // Read from GPR
  selectedIn.preExeInstInfo.gprReadPorts.zip(io.peer.get.gprReadPorts).foreach {
    case (info, port) =>
      port.en   := info.en
      port.addr := info.addr
  }

  // Read from CSR
  io.peer.get.csrReadPorts(0).en   := selectedIn.preExeInstInfo.csrReadEn
  io.peer.get.csrReadPorts(0).addr := selectedIn.instInfo.csrWritePort.addr

  // Determine left and right operands
  when(selectedIn.preExeInstInfo.isHasImm) {
    resultOutReg.bits.rightOperand := selectedIn.preExeInstInfo.imm
  }
  Seq(resultOutReg.bits.leftOperand, resultOutReg.bits.rightOperand)
    .zip(io.peer.get.gprReadPorts)
    .foreach {
      case (operand, gprReadPort) =>
        when(gprReadPort.en) {
          operand := gprReadPort.data
        }
    }

  resultOutReg.valid := selectedIn.instInfo.isValid

  resultOutReg.bits.exeSel       := selectedIn.preExeInstInfo.exeSel
  resultOutReg.bits.exeOp        := selectedIn.preExeInstInfo.exeOp
  resultOutReg.bits.gprWritePort := selectedIn.preExeInstInfo.gprWritePort
  // jumbBranch / memLoadStort / csr
  resultOutReg.bits.jumpBranchAddr := selectedIn.preExeInstInfo.jumpBranchAddr
  when(selectedIn.preExeInstInfo.csrReadEn) {
    resultOutReg.bits.csrData := io.peer.get.csrReadPorts(0).data
  }
  resultOutReg.bits.instInfo := selectedIn.instInfo
}
