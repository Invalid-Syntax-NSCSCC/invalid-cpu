package pipeline.dispatch

import chisel3._
import chisel3.util._
import common.bundles.{RfAccessInfoNdPort, RfReadPort,RfWriteNdPort}
import bundles.{ExeInstNdPort, IssuedInfoNdPort}
import spec._
import pipeline.ctrl.bundles.PipelineControlNDPort

class RegReadStage(readNum: Int = Param.instRegReadNum) extends Module {
  val io = IO(new Bundle {
    // `IssueStage` -> `RegReadStage`
    val issuedInfoPort = Input(new IssuedInfoNdPort)
    // `RegReadStage` <-> `Regfile`
    val gprReadPorts   = Vec(readNum, Flipped(new RfReadPort))

    // `RegReadStage` -> `ExeStage` (next clock pulse)
    val exeInstPort = Output(new ExeInstNdPort)

    // 数据前推
    // `ExeStage` -> `RegReadStage`
    val exeRfWriteFeedbackPort = Input(new RfWriteNdPort)

    // `pipeline control signal
    // `CtrlStage` -> `RegReadStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)
  })

  // 暂停 目前仅实现对exeInstPort暂停
  val stall = WireDefault(io.pipelineControlPort.stall)

  // Pass to the next stage in a sequential way
  val exeInstReg = RegInit(ExeInstNdPort.default)
  io.exeInstPort := exeInstReg

  // Pass through write-back info
  exeInstReg.gprWritePort := Mux(
    stall,
    RfAccessInfoNdPort.default,
    io.issuedInfoPort.info.gprWritePort
  )

  // Read from GPR
  io.gprReadPorts.zip(io.issuedInfoPort.info.gprReadPorts).foreach {
    case (port, info) =>
      port.en   := info.en
      port.addr := info.addr
  }

  // Determine left and right operands
  exeInstReg.leftOperand  := zeroWord
  exeInstReg.rightOperand := zeroWord
  when(~stall) {
    // when(io.issuedInfoPort.info.gprReadPorts(0).en) {
    //   exeInstReg.leftOperand := io.gprReadPorts(0).data

    // }

    // when(io.issuedInfoPort.info.gprReadPorts(1).en) {
    //   exeInstReg.rightOperand := io.gprReadPorts(1).data

    // }.elsewhen(io.issuedInfoPort.info.isHasImm) {
    //   exeInstReg.rightOperand := io.issuedInfoPort.info.imm
    // }
      when(io.issuedInfoPort.info.isHasImm) {
        exeInstReg.rightOperand := io.issuedInfoPort.info.imm
      }
      Seq(exeInstReg.leftOperand, exeInstReg.rightOperand)
        .zip(io.gprReadPorts)
        .foreach{
          case (oprand, gprReadPort) => {
            when(
              gprReadPort.en && 
              io.exeRfWriteFeedbackPort.en && 
              gprReadPort.addr === io.exeRfWriteFeedbackPort.addr
            ) {
              oprand := io.exeRfWriteFeedbackPort.data
            }.elsewhen(gprReadPort.en) {
              oprand := gprReadPort.data
            }
          }
      }
  }
  

  // Pass execution instruction if valid
  exeInstReg.exeSel       := ExeInst.Sel.none
  exeInstReg.exeOp        := ExeInst.Op.nop
  exeInstReg.gprWritePort := RfAccessInfoNdPort.default
  when(~stall) {
    when(io.issuedInfoPort.isValid) {
      exeInstReg.exeSel       := io.issuedInfoPort.info.exeSel
      exeInstReg.exeOp        := io.issuedInfoPort.info.exeOp
      exeInstReg.gprWritePort := io.issuedInfoPort.info.gprWritePort
    }
  }
}
