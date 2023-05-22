package spec

import chisel3._

object ExeInst {
  object Sel {
    var count = 0

    private def next = {
      count += 1
      count.U
    }

    val none       = 0.U
    val logic      = next
    val shift      = next
    val arithmetic = next // Only for regular arithmetic operation computed in ALU
    val jumpBranch = next
    val loadStore  = next
  }

  object Op {
    var count = 0

    private def next = {
      count += 1
      count.U
    }

    val nop   = 0.U
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
    val mul   = next // C
    val mulh  = next
    val mulhu = next
    val div   = next // F
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

    // load store
    val ld_b  = next
    val ld_h  = next
    val ld_w  = next
    val st_b  = next
    val st_h  = next
    val st_w  = next
    val ld_bu = next
    val ld_hu = next
    val ll    = next
    val sc    = next
    val preld = next
    val dbar  = next
    val ibar  = next

    // csr
    val csrrd   = next
    val csrwr   = next
    val csrxchg = next

    // exception
    val break_  = next
    val syscall = next
    val ertn    = next

    // stable counter
    val rdcntvl_w = next
    val rdcntvh_w = next
    val rdcntid   = next

    object Tlb {
      val clrAll          = 0.U(Width.Tlb.op)
      val clrAllAlt       = 1.U(Width.Tlb.op)
      val clrGlobl        = 2.U(Width.Tlb.op)
      val clrNGlobl       = 3.U(Width.Tlb.op)
      val clrNGloblAsId   = 4.U(Width.Tlb.op)
      val clrNGloblAsIdVa = 5.U(Width.Tlb.op)
      val clrGloblAsIdVa  = 6.U(Width.Tlb.op)
    }
  }
}
