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
  })

  // Pass to the next stage in a sequential way
  io.issuedInfoPort.ready := false.B
  val outputValidReg = RegNext(false.B)
  io.exeInstPort.valid := outputValidReg
  val exeInstReg = RegInit(ExeInstNdPort.default)
  io.exeInstPort.bits := exeInstReg

  // Read from GPR
  io.gprReadPorts.zip(io.issuedInfoPort.bits.preExeInstInfo.gprReadPorts).foreach {
    case (port, info) =>
      port.en   := info.en
      port.addr := info.addr
  }

  // Read from CSR
  io.csrReadPorts(0).en   := io.issuedInfoPort.bits.preExeInstInfo.csrReadEn
  io.csrReadPorts(0).addr := io.issuedInfoPort.bits.instInfo.csrWritePort.addr

  // read from dataforward
  // io.gprReadPorts.zip(io.dataforwardPorts).foreach {
  //   case ((gprRead, dataforward)) =>
  //     dataforward.en   := gprRead.en
  //     dataforward.addr := gprRead.addr
  // }

  when(io.issuedInfoPort.valid && io.exeInstPort.ready) {

    io.issuedInfoPort.ready := true.B
    outputValidReg          := true.B

    // Determine left and right operands

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

    // Pass execution instruction if valid

    io.issuedInfoPort.ready := true.B
    outputValidReg          := true.B

    exeInstReg.exeSel       := io.issuedInfoPort.bits.preExeInstInfo.exeSel
    exeInstReg.exeOp        := io.issuedInfoPort.bits.preExeInstInfo.exeOp
    exeInstReg.gprWritePort := io.issuedInfoPort.bits.preExeInstInfo.gprWritePort
    // jumbBranch / memLoadStort / csr
    exeInstReg.jumpBranchAddr := io.issuedInfoPort.bits.preExeInstInfo.jumpBranchAddr
    when(io.issuedInfoPort.bits.preExeInstInfo.csrReadEn) {
      exeInstReg.csrData := io.csrReadPorts(0).data
    }

    exeInstReg.instInfo := io.issuedInfoPort.bits.instInfo
  }

  // flush all regs
  when(io.pipelineControlPort.flush) {
    outputValidReg          := false.B
    exeInstReg              := ExeInstNdPort.default
    io.issuedInfoPort.ready := false.B
    io.exeInstPort.valid    := false.B
  }
}
