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
    val logic      = next
    val shift      = next
    val arithmetic = next // Only for regular arithmetic operation computed in ALU
    val jumpBranch = next
  }

  object Op {
    private var count = 0

    private def next = {
      count += 1
      count.U(Param.Width.exeOp)
    }

    val nop   = 0.U(Param.Width.exeOp)
    val add   = next
    val sub   = next
    val slt   = next
    val sltu  = next
    val nor   = next
    val and   = next
    val or    = next
    val xor   = next
    val sll   = next
    val srl   = next
    val sra   = next
    val mul   = next
    val mulh  = next
    val mulhu = next
    val div   = next
    val divu  = next
    val mod   = next
    val modu  = next
    // branch
    val jirl = next
    val b    = next
    val bl   = next
    val beq  = next
    val bne  = next
    val blt  = next
    val bge  = next
    val bltu = next
    val bgeu = next
  }
}
