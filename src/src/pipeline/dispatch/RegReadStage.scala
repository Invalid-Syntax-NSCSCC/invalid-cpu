package pipeline.dispatch

import chisel3._
import chisel3.util._
import common.bundles.{RfAccessInfoNdPort, RfReadPort}
import bundles.{ExeInstNdPort, IssuedInfoNdPort}
import spec._

class RegReadStage(readNum: Int = Param.instRegReadNum) extends Module {
  val io = IO(new Bundle {
    val issuedInfoPort = Input(new IssuedInfoNdPort)
    val gprReadPorts   = Vec(readNum, Flipped(new RfReadPort))

    // `RegReadStage` -> `ExeStage` (next clock pulse)
    val exeInstPort = Output(new ExeInstNdPort)
  })

  // Pass to the next stage in a sequential way
  val exeInstReg = RegInit(ExeInstNdPort.default)
  io.exeInstPort := exeInstReg

  // Pass through write-back info
  exeInstReg.gprWritePort := io.issuedInfoPort.info.gprWritePort

  // Read from GPR
  io.gprReadPorts.zip(io.issuedInfoPort.info.gprReadPorts).foreach {
    case (port, info) =>
      port.en   := info.en
      port.addr := info.addr
  }

  // Determine left and right operands
  exeInstReg.leftOperand  := zeroWord
  exeInstReg.rightOperand := zeroWord
  when(io.issuedInfoPort.info.gprReadPorts(0).en) {
    exeInstReg.leftOperand := io.gprReadPorts(0).data
  }
  when(io.issuedInfoPort.info.gprReadPorts(1).en) {
    exeInstReg.rightOperand := io.gprReadPorts(1).data
  }.elsewhen(io.issuedInfoPort.info.isHasImm) {
    exeInstReg.rightOperand := io.issuedInfoPort.info.imm
  }

  // Pass execution instruction if valid
  exeInstReg.exeSel       := ExeInst.Sel.none
  exeInstReg.exeOp        := ExeInst.Op.nop
  exeInstReg.gprWritePort := RfAccessInfoNdPort.default
  when(io.issuedInfoPort.isValid) {
    exeInstReg.exeSel       := io.issuedInfoPort.info.exeSel
    exeInstReg.exeOp        := io.issuedInfoPort.info.exeOp
    exeInstReg.gprWritePort := io.issuedInfoPort.info.gprWritePort
  }
}
