package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class MemLoadStoreInfoNdPort extends Bundle {
  val exeOp = UInt(Param.Width.exeOp)
  val vaddr = UInt(Width.Reg.data)
  val data  = UInt(Width.Reg.data)
}

object MemLoadStoreInfoNdPort {
  val default = (new MemLoadStoreInfoNdPort).Lit(
    _.exeOp -> 0.U,
    _.vaddr -> zeroWord,
    _.data -> zeroWord
  )
}
