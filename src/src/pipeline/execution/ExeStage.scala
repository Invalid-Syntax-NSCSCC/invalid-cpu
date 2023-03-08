package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ExeInstNdPort
import spec.ExeInst.Sel
import spec._

class ExeStage(readNum: Int = Param.instRegReadNum) extends Module {
  val io = IO(new Bundle {
    val exeInstPort = Input(new ExeInstNdPort)

    // TODO: Add `MemStage` in between
    // `ExeStage` -> `WbStage` (next clock pulse)
    val gprWritePort = Output(new RfWriteNdPort)
  })

  // Pass to the next stage in a sequential way
  val gprWriteReg = RegInit(RfWriteNdPort.default)
  io.gprWritePort := gprWriteReg

  // ALU module
  val alu = Module(new Alu)
  alu.io.aluInst.op           := io.exeInstPort.exeOp
  alu.io.aluInst.leftOperand  := io.exeInstPort.leftOperand
  alu.io.aluInst.rightOperand := io.exeInstPort.rightOperand

  // Pass write-back information
  gprWriteReg.en   := io.exeInstPort.gprWritePort.en
  gprWriteReg.addr := io.exeInstPort.gprWritePort.addr

  // Result fallback
  gprWriteReg.data := zeroWord

  // Result selection
  switch(io.exeInstPort.exeSel) {
    is(Sel.arithmetic) {
      gprWriteReg.data := alu.io.result.arithmetic
    }
  }
}
