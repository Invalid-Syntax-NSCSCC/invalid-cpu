package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ExeInstNdPort
import spec.ExeInst.Sel
import spec._
import pipeline.ctrl.bundles.PipelineControlNDPort

class ExeStage(readNum: Int = Param.instRegReadNum) extends Module {
  val io = IO(new Bundle {
    val exeInstPort = Input(new ExeInstNdPort)

    // TODO: Add `MemStage` in between
    // `ExeStage` -> `WbStage` (next clock pulse)
    val gprWritePort = Output(new RfWriteNdPort)

    // pipeline control signal
    // `CtrlStage` -> `ExeStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)
    // `Exestage` -> `CtrlStage`
    val stallRequest = Output(Bool())
    // exception
    val divisorZeroException = Output(Bool())
  })

  // store exeInst
  val exeInstStore = RegInit(ExeInstNdPort.default)

  // Pass to the next stage in a sequential way
  val gprWriteReg = RegInit(RfWriteNdPort.default)
  io.gprWritePort := gprWriteReg

  // ALU module
  val alu = Module(new Alu)

  val stallRequest      = alu.io.stallRequest
  val stallRequestDelay = RegNext(stallRequest, false.B)
  io.stallRequest := stallRequest

  alu.io.aluInst.op             := Mux(stallRequestDelay, exeInstStore.exeOp, io.exeInstPort.exeOp)
  alu.io.aluInst.leftOperand    := Mux(stallRequestDelay, exeInstStore.leftOperand, io.exeInstPort.leftOperand)
  alu.io.aluInst.rightOperand   := Mux(stallRequestDelay, exeInstStore.rightOperand, io.exeInstPort.rightOperand)
  alu.io.aluInst.jumpBranchAddr := Mux(stallRequestDelay, exeInstStore.jumpBranchAddr, io.exeInstPort.jumpBranchAddr)
  io.divisorZeroException       := alu.io.divisorZeroException

  exeInstStore := Mux(
    stallRequestDelay,
    exeInstStore,
    io.exeInstPort
  )

  // Pass write-back information
  // gprWriteReg.en   := (io.exeInstPort.gprWritePort.en | exeInstStore.gprWritePort.en ) & ~stallRequest & (exeInstStore.leftOperand.orR | exeInstStore.exeOp.orR | exeInstStore.rightOperand.orR)
  // gprWriteReg.addr := Mux(stallRequest, zeroWord, io.exeInstPort.gprWritePort.addr) 44
  gprWriteReg.en   := false.B
  gprWriteReg.addr := zeroWord
  val useSel = WireDefault(0.U(Param.Width.exeSel))
  when(stallRequestDelay & ~stallRequest) {
    // with stall like mul / div that take more than 1 cycle
    gprWriteReg.en   := exeInstStore.gprWritePort.en
    gprWriteReg.addr := exeInstStore.gprWritePort.addr
    useSel           := exeInstStore.exeSel
  }.elsewhen(~stallRequest) {
    // normal inst that take 1 cycle
    gprWriteReg.en   := io.exeInstPort.gprWritePort.en
    gprWriteReg.addr := io.exeInstPort.gprWritePort.addr
    useSel           := io.exeInstPort.exeSel
  }

  // Result fallback
  gprWriteReg.data := zeroWord

  // Result selection
  when(~stallRequest) {
    switch(useSel) {
      is(Sel.logic) {
        gprWriteReg.data := alu.io.result.logic
      }
      is(Sel.shift) {
        gprWriteReg.data := alu.io.result.shift
      }
      is(Sel.arithmetic) {
        gprWriteReg.data := alu.io.result.arithmetic
      }
      is(Sel.jumpBranch) {
        gprWriteReg.data := io.exeInstPort.pcAddr + 4.U
      }
    }
  }

}
