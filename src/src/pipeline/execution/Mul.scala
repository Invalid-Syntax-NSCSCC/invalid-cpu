package pipeline.execution

import chisel3._
import chisel3.util._
import pipeline.execution.bundles.MulDivInstNdPort
import spec.doubleWordLength

object MulState extends ChiselEnum {
  val free, calc, finish = Value
}

class Mul extends Module {
  val io = IO(new Bundle {
    val mulInst   = Input(Valid(new MulDivInstNdPort))
    val mulResult = Output(Valid(UInt(doubleWordLength.W)))
    val isFlush   = Input(Bool())
  })

  val stateReg = RegInit(MulState.free)

  val lopReg      = RegNext(io.mulInst.bits.leftOperand)
  val ropReg      = RegNext(io.mulInst.bits.rightOperand)
  val isSignedReg = RegNext(io.mulInst.bits.isSigned)

  val signedResult   = RegNext(lopReg.asSInt * ropReg.asSInt)
  val unsignedResult = RegNext(lopReg * ropReg)

  io.mulResult.valid := false.B
  io.mulResult.bits  := DontCare

  switch(stateReg) {
    is(MulState.free) {
      when(io.mulInst.valid) {
        stateReg := MulState.calc
      }
    }
  }
  is(MulState.calc) {
    stateReg := MulState.finish
  }
  is(MulState.finish) {
    stateReg           := MulState.free
    io.mulResult.valid := true.B
    io.mulResult.bits := Mux(
      isSignedReg,
      signedResult.asUInt,
      unsignedResult
    )
  }

  when(io.isFlush) {
    stateReg := MulState.free
  }
}
