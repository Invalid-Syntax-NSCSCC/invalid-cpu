package pipeline.mem.bundles

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class MemLoadStorePort extends Bundle {
  val valid     = Input(Bool())
  val mode      = Input(Bool())
  val memSel    = Input(UInt(Param.Width.memSel))
  val addr      = Input(UInt(Width.Reg.data))
  val dataWrite = Input(UInt(Width.Reg.data))
  val dataRead  = Output(UInt(Width.Reg.data))
}
