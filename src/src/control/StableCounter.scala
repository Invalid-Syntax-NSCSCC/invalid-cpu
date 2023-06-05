package control

import chisel3._
import spec.doubleWordLength
import spec._
import chisel3.util._
import control.bundles.StableCounterReadPort
import spec.Param.isDiffTest

class StableCounter extends Module {

  val io = IO(new StableCounterReadPort)

  val timer64 = RegInit(0.U(doubleWordLength.W))
  timer64 := timer64 + 1.U

  val timerId = RegInit(zeroWord)

  io.output  := zeroWord
  io.isMatch := false.B
  switch(io.exeOp) {
    is(ExeInst.Op.rdcntid) {
      io.isMatch := true.B
      io.output  := timerId
    }

    is(ExeInst.Op.rdcntvl_w) {
      io.isMatch := true.B
      io.output  := timer64(wordLength - 1, 0)
    }

    is(ExeInst.Op.rdcntvh_w) {
      io.isMatch := true.B
      io.output  := timer64(doubleWordLength - 1, wordLength)
    }
  }

  if (isDiffTest) {
    io.difftest match {
      case Some(d) =>
        d.isCnt := RegNext(io.isMatch)
        d.value := RegNext(timer64)
      case None =>
    }
  }
}
