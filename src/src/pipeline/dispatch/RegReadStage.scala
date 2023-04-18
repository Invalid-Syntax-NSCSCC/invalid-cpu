package pipeline.dispatch

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfReadPort, RfWriteNdPort}
import bundles.{ExeInstNdPort, IssuedInfoNdPort}
import chisel3.experimental.BundleLiterals._
import spec._
import control.bundles.PipelineControlNDPort
import pipeline.writeback.bundles.InstInfoNdPort
import control.bundles.CsrReadPort
import pipeline.dataforward.bundles.DataForwardReadPort

class RegReadStage(readNum: Int = Param.instRegReadNum, csrRegsReadNum: Int = Param.csrRegsReadNum) extends Module {
  val io = IO(new Bundle {
    // `IssueStage` -> `RegReadStage`
    val issuedInfoPort = Input(new IssuedInfoNdPort)
    // `RegReadStage` <-> `Regfile`
    val gprReadPorts = Vec(readNum, Flipped(new RfReadPort))

    // `RegReadStage <-> `Csr`
    val csrReadPorts = Vec(csrRegsReadNum, Flipped(new CsrReadPort))

    // `RegReadStage` -> `ExeStage` (next clock pulse)
    val exeInstPort = Output(new ExeInstNdPort)

    // 数据前推
    // `DataForwardStage` -> `RegReadStage`
    // val dataforwardPorts = Vec(readNum, Flipped(new DataForwardReadPort))

    // `pipeline control signal
    // `Cu` -> `RegReadStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)

    // (next clock pulse)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)
  })

  // Wb debug port connection
  val instInfoReg = Reg(new InstInfoNdPort)
  instInfoReg                    := io.instInfoPassThroughPort.in
  io.instInfoPassThroughPort.out := instInfoReg

  val stallFromCtrl = WireDefault(io.pipelineControlPort.stall)

  // Pass to the next stage in a sequential way
  val exeInstReg = RegInit(ExeInstNdPort.default)
  io.exeInstPort := exeInstReg

  // Read from GPR
  io.gprReadPorts.zip(io.issuedInfoPort.info.gprReadPorts).foreach {
    case (port, info) =>
      port.en   := info.en
      port.addr := info.addr
  }

  // Read from CSR
  io.csrReadPorts(0).en   := io.issuedInfoPort.info.csrReadEn
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
  when(!stallFromCtrl) {
    when(io.issuedInfoPort.info.isHasImm) {
      exeInstReg.rightOperand := io.issuedInfoPort.info.imm
    }
    Seq(exeInstReg.leftOperand, exeInstReg.rightOperand)
      .lazyZip(io.gprReadPorts)
      // .lazyZip(io.dataforwardPorts)
      .foreach {
        // case (oprand, gprReadPort, dataforward) =>
        case (oprand, gprReadPort) =>
          // when(
          //   gprReadPort.en &&
          //     io.exeRfWriteFeedbackPort.en &&
          //     gprReadPort.addr === io.exeRfWriteFeedbackPort.addr
          // ) {
          //   oprand := io.exeRfWriteFeedbackPort.data
          // }.elsewhen(gprReadPort.en) {
          //   oprand := gprReadPort.data
          // }
          when(gprReadPort.en) {
            // oprand := Mux(dataforward.valid, dataforward.data, gprReadPort.data)
            oprand := gprReadPort.data
          }
      }
  }

  // Pass execution instruction if valid

  exeInstReg.exeSel       := ExeInst.Sel.none
  exeInstReg.exeOp        := ExeInst.Op.nop
  exeInstReg.gprWritePort := RfAccessInfoNdPort.default
  when(!stallFromCtrl) {
    when(io.issuedInfoPort.isValid) {
      exeInstReg.exeSel       := io.issuedInfoPort.info.exeSel
      exeInstReg.exeOp        := io.issuedInfoPort.info.exeOp
      exeInstReg.gprWritePort := io.issuedInfoPort.info.gprWritePort
      // jumbBranch / memLoadStort / csr
      exeInstReg.jumpBranchAddr := io.issuedInfoPort.info.jumpBranchAddr
      when(io.issuedInfoPort.info.csrReadEn) {
        exeInstReg.csrData := io.csrReadPorts(0).data
      }

      // switch(io.issuedInfoPort.info.exeOp) {
      //   is(ExeInst.Op.csrrd) {
      //     instInfoReg.csrWritePort.en   := false.B
      //     instInfoReg.csrWritePort.addr := io.issuedInfoPort.info.csrAddr
      //   }
      //   is(ExeInst.Op.csrwr) {
      //     instInfoReg.csrWritePort.en   := true.B
      //     instInfoReg.csrWritePort.addr := io.issuedInfoPort.info.csrAddr
      //   }
      //   is(ExeInst.Op.csrxchg) {
      //     instInfoReg.csrWritePort.en   := true.B
      //     instInfoReg.csrWritePort.addr := io.issuedInfoPort.info.csrAddr
      //   }
      // }
    }
  }

  // clear
  when(io.pipelineControlPort.clear) {
    InstInfoNdPort.setDefault(instInfoReg)
    exeInstReg := ExeInstNdPort.default
  }
  // flush all regs
  when(io.pipelineControlPort.flush) {
    InstInfoNdPort.setDefault(instInfoReg)
    exeInstReg := ExeInstNdPort.default
  }
}
