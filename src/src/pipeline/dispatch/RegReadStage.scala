package pipeline.dispatch

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfReadPort, RfWriteNdPort}
import bundles.{ExeInstNdPort, IssuedInfoNdPort}
import chisel3.experimental.BundleLiterals._
import spec._
import control.bundles.PipelineControlNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import control.bundles.CsrReadPort
// import pipeline.dataforward.bundles.DataForwardReadPort

class RegReadStage(readNum: Int = Param.instRegReadNum, csrRegsReadNum: Int = Param.csrRegsReadNum) extends Module {
  val io = IO(new Bundle {
    // `IssueStage` -> `RegReadStage`
    val issuedInfoPort = Flipped(Decoupled(new IssuedInfoNdPort))
    // `RegReadStage` <-> `Regfile`
    val gprReadPorts = Vec(readNum, Flipped(new RfReadPort))

    // `RegReadStage <-> `Csr`
    val csrReadPorts = Vec(csrRegsReadNum, Flipped(new CsrReadPort))

    // `RegReadStage` -> `ExeStage` (next clock pulse)
    val exeInstPort = Decoupled(new ExeInstNdPort)

    // 数据前推
    // `DataForwardStage` -> `RegReadStage`
    // val dataforwardPorts = Vec(readNum, Flipped(new DataForwardReadPort))

    // `pipeline control signal
    // `Cu` -> `RegReadStage`
    val pipelineControlPort = Input(new PipelineControlNdPort)

    // (next clock pulse)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)
  })

  // Wb debug port connection
  val instInfoReg = RegNext(io.instInfoPassThroughPort.in)
  io.instInfoPassThroughPort.out := instInfoReg

  // Pass to the next stage in a sequential way
  val exeInstReg = RegInit(ExeInstNdPort.default)
  io.exeInstPort := exeInstReg

  // Read from GPR
  io.gprReadPorts.zip(io.issuedInfoPort.bits.preExeInstInfo.gprReadPorts).foreach {
    case (port, info) =>
      port.en   := info.en
      port.addr := info.addr
  }

  // Read from CSR
  io.csrReadPorts(0).en   := io.issuedInfoPort.bits.preExeInstInfo.csrReadEn
  io.csrReadPorts(0).addr := io.instInfoPassThroughPort.in.csrWritePort.addr

  // read from dataforward
  // io.gprReadPorts.zip(io.dataforwardPorts).foreach {
  //   case ((gprRead, dataforward)) =>
  //     dataforward.en   := gprRead.en
  //     dataforward.addr := gprRead.addr
  // }

  // Determine left and right operands
  exeInstReg.leftOperand  := zeroWord
  exeInstReg.rightOperand := zeroWord
  when(io.issuedInfoPort.valid) {
    
    when(io.issuedInfoPort.bits.preExeInstInfo.isHasImm) {
      exeInstReg.rightOperand := io.issuedInfoPort.bits.preExeInstInfo.imm
    }
    Seq(exeInstReg.leftOperand, exeInstReg.rightOperand)
      .lazyZip(io.gprReadPorts)
      .foreach {
        case (oprand, gprReadPort) =>
          when(gprReadPort.en) {
            oprand := gprReadPort.data
          }
      }
  }

  // Pass execution instruction if valid

  exeInstReg.exeSel       := ExeInst.Sel.none
  exeInstReg.exeOp        := ExeInst.Op.nop
  exeInstReg.gprWritePort := RfAccessInfoNdPort.default
  when(!io.pipelineControlPort.stall) {
    when(io.issuedInfoPort.isValid) {
      exeInstReg.exeSel       := io.issuedInfoPort.info.exeSel
      exeInstReg.exeOp        := io.issuedInfoPort.info.exeOp
      exeInstReg.gprWritePort := io.issuedInfoPort.info.gprWritePort
      // jumbBranch / memLoadStort / csr
      exeInstReg.jumpBranchAddr := io.issuedInfoPort.info.jumpBranchAddr
      when(io.issuedInfoPort.info.csrReadEn) {
        exeInstReg.csrData := io.csrReadPorts(0).data
      }
    }
  }

  // clear
  when(io.pipelineControlPort.clear) {
    InstInfoNdPort.invalidate(instInfoReg)
    exeInstReg := ExeInstNdPort.default
  }

  // flush all regs
  when(io.pipelineControlPort.flush) {
    InstInfoNdPort.invalidate(instInfoReg)
    exeInstReg := ExeInstNdPort.default
  }
}
