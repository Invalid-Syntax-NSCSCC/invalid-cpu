package spec

import chisel3._

object ExeInst {
  object Sel {
    private var count = 0
    private def next = {
      count += 1
      count.U(Param.Width.exeSel)
    }

    val none       = 0.U(Param.Width.exeSel)
    val arithmetic = next // Only for regular arithmetic operation computed in ALU
  }
  object Op {
    private var count = 0
    private def next = {
      count += 1
      count.U(Param.Width.exeOp)
    }

    val nop = 0.U(Param.Width.exeOp)
    val add = next
  }
}
