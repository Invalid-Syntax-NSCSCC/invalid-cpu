package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._

class MemLoadStoreInfoNdPort extends Bundle {
  val exeOp = UInt(Param.Width.exeOp)
  val vaddr = UInt(Width.Reg.data)
  val data  = UInt(Width.Reg.data)
}
