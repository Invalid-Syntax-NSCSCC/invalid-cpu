package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ExeInstNdPort
import spec.ExeInst.Sel
import spec._
import pipelie.bundles.PipelineControlNDPort

class ExeStage(readNum: Int = Param.instRegReadNum) extends Module {
  val io = IO(new Bundle {
    val exeInstPort = Input(new ExeInstNdPort)

    // TODO: Add `MemStage` in between
    // `ExeStage` -> `WbStage` (next clock pulse)
    val gprWritePort = Output(new RfWriteNdPort)

    // pipeline control signal
    val pipelineControlPort = Input(new PipelineControlNDPort)
    val advance = Output(Bool())
  })

  // Pass to the next stage in a sequential way
  val gprWriteReg = RegInit(RfWriteNdPort.default)
  io.gprWritePort := gprWriteReg

  // ALU module
  val alu = Module(new Alu)
  alu.io.aluInst.op           := io.exeInstPort.exeOp
  alu.io.aluInst.leftOperand  := io.exeInstPort.leftOperand
  alu.io.aluInst.rightOperand := io.exeInstPort.rightOperand
  io.advance                  := alu.io.advance

  // Pass write-back information
  gprWriteReg.en   := io.exeInstPort.gprWritePort.en
  gprWriteReg.addr := io.exeInstPort.gprWritePort.addr

  // Result fallback
  gprWriteReg.data := zeroWord

  // Result selection
  switch(io.exeInstPort.exeSel) {
    is(Sel.logic) {
      gprWriteReg.data := alu.io.result.logic
    }
    is(Sel.shift) {
      gprWriteReg.data := alu.io.result.shift
    }
    is(Sel.arithmetic) {
      gprWriteReg.data := alu.io.result.arithmetic
    }
  }
}