package pipeline.dispatch

import chisel3._
import chisel3.util._
import common.bundles.RfReadPort
import pipeline.dispatch.bundles.{IssuedInfoNdPort, MicrocodeNdPort}
import spec._

class RegReadStage extends Module {
  val io = IO(new Bundle {
    val issuedInfoPort = Input(new IssuedInfoNdPort)
    val gprReadPorts   = Vec(2, new RfReadPort)
    val microcodePort  = Output(new MicrocodeNdPort)
  })

  // Read from GPR
  io.gprReadPorts.zip(io.issuedInfoPort.info.gprReadPorts).foreach {
    case (port, info) =>
      port.en   := info.en
      port.addr := info.addr
  }

  // Determine left and right operands
  io.microcodePort.leftOperand  := zeroWord
  io.microcodePort.rightOperand := zeroWord
  when(io.issuedInfoPort.info.gprReadPorts(0).en) {
    io.microcodePort.leftOperand := io.gprReadPorts(0).data
  }
  when(io.issuedInfoPort.info.gprReadPorts(1).en) {
    io.microcodePort.rightOperand := io.gprReadPorts(1).data
  }.elsewhen(io.issuedInfoPort.info.isHasImm) {
    io.microcodePort.rightOperand := io.issuedInfoPort.info.imm
  }

  io.microcodePort.exeSel := Mux(
    io.issuedInfoPort.isValid,
    io.issuedInfoPort.info.exeSel,
    ExeInst.Sel.none
  )
  io.microcodePort.exeOp := Mux(
    io.issuedInfoPort.isValid,
    io.issuedInfoPort.info.exeOp,
    ExeInst.Op.nop
  )
}
