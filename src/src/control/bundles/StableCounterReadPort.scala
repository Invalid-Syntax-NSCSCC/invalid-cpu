package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class StableCounterReadPort extends Bundle {
  val exeOp   = Input(UInt(Param.Width.exeOp))
  val isMatch = Output(Bool())
  val output  = Output(UInt(Width.Reg.data))
}
