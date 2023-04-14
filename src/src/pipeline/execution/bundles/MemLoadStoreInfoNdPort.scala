package pipeline.execution.bundles

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor

class MemLoadStoreInfoNdPort extends Bundle {
  val vaddr = UInt(Width.Reg.data)
  val data  = UInt(Width.Reg.data)
}

object MemLoadStoreInfoNdPort {
  val default = (new MemLoadStoreInfoNdPort).Lit(
    _.vaddr -> zeroWord,
    _.data -> zeroWord
  )
}
