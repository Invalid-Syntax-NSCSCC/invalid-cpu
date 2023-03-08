package pipeline.execution

import chisel3._
import chisel3.util._
import pipeline.execution.bundles.{AluInstNdPort, AluResultNdPort}
import spec._
import ExeInst.Op

class Alu extends Module {
  val io = IO(new Bundle {
    val aluInst = Input(new AluInstNdPort)
    val result  = Output(new AluResultNdPort)
  })

  io.result := DontCare

  // Arithmetic computation
  switch(io.aluInst.op) {
    is(Op.add) {
      io.result.arithmetic := (io.aluInst.leftOperand.asSInt + io.aluInst.rightOperand.asSInt).asUInt
    }
  }
}
