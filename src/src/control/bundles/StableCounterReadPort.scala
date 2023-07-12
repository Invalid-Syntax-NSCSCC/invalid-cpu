package control.bundles

import chisel3._
import spec._

class StableCounterReadPort extends Bundle {
  val output = Output(UInt(doubleWordLength.W))
  // val difftest = if (isDiffTest) {
  //   Some(Output(new Bundle() {
  //     val isCnt = Output(Bool())
  //     val value = Output(UInt(doubleWordLength.W))
  //   }))
  // } else None
}
