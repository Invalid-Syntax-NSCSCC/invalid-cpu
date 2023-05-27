package pipeline.dispatch

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfReadPort, RfWriteNdPort}
import pipeline.execution.ExeNdPort
import chisel3.experimental.BundleLiterals._
import spec._
import control.bundles.PipelineControlNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import control.bundles.CsrReadPort
import pipeline.common.BaseStage
import chisel3.experimental.BundleLiterals._
import common.bundles.RfAccessInfoNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.dispatch.bundles.PreExeInstNdPort

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

class RegReadPeerPort(readNum: Int, csrRegsReadNum: Int) extends Bundle {
  // `RegReadStage` <-> `Regfile`
  val gprReadPorts = Vec(readNum, Flipped(new RfReadPort))

  // `RegReadStage <-> `Csr`
  val csrReadPorts = Vec(csrRegsReadNum, Flipped(new CsrReadPort))

}

class RegReadStage(readNum: Int = Param.instRegReadNum, csrRegsReadNum: Int = Param.csrRegsReadNum)
    extends BaseStage(
      new RegReadNdPort,
      new ExeNdPort,
      RegReadNdPort.default,
      Some(new RegReadPeerPort(readNum, csrRegsReadNum))
    ) {

  require(csrRegsReadNum == 1)

  // Read from GPR
  selectedIn.preExeInstInfo.gprReadPorts.zip(io.peer.get.gprReadPorts).foreach {
    case (info, port) =>
      port.en   := info.en
      port.addr := info.addr
  }

  // Read from CSR
  io.peer.get.csrReadPorts(0).en := selectedIn.preExeInstInfo.csrReadEn
  io.peer.get.csrReadPorts(0).addr := selectedIn.instInfo.csrWritePort.addr

  // Determine left and right operands
  when(selectedIn.preExeInstInfo.isHasImm) {
    resultOutReg.bits.rightOperand := selectedIn.preExeInstInfo.imm
  }
  Seq(resultOutReg.bits.leftOperand, resultOutReg.bits.rightOperand)
    .zip(io.peer.get.gprReadPorts)
    .foreach {
      case (oprand, gprReadPort) =>
        when(gprReadPort.en) {
          oprand := gprReadPort.data
        }
    }

  isComputed         := selectedIn.instInfo.isValid
  resultOutReg.valid := true.B

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
