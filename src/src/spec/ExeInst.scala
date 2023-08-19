package spec

import chisel3._
import chisel3.experimental.BundleLiterals._

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

  object Width {
    val sel   = 3.W
    val subOp = 3.W
  }

  class OpBundle extends Bundle {
    val sel   = UInt(Width.sel)
    val subOp = UInt(Width.subOp)
  }

  object OpBundle {
    var selCount   = -1
    var subOpCount = -1

    private def next: OpBundle = {
      subOpCount += 1
      (new OpBundle).Lit(
        _.sel -> selCount.U,
        _.subOp -> subOpCount.U
      )
    }

    private def newSel: UInt = {
      selCount += 1
      subOpCount = -1
      selCount.U
    }

    // read time / shift
    val sel_readTimeOrShift = newSel

    val nop       = next
    val sll       = next
    val srl       = next
    val sra       = next
    val rdcntvl_w = next
    val rdcntvh_w = next

    // simple arthmetic fn / logic
    val sel_arthOrLogic = newSel

    val add  = next
    val sub  = next
    val slt  = next
    val sltu = next
    val nor  = next
    val and  = next
    val or   = next
    val xor  = next

    // mul / div
    val sel_mulDiv = newSel

    val div   = next
    val divu  = next
    val mod   = next
    val modu  = next
    val mul   = next
    val mulh  = next
    val mulhu = next

    // jump excp jirl
    val sel_simpleBranch = newSel

    val b    = next
    val bl   = next
    val beq  = next
    val bne  = next
    val blt  = next
    val bltu = next
    val bge  = next
    val bgeu = next

    // other
    val sel_misc = newSel

    val jirl  = next
    val preld = next
    val dbar  = next
    val ibar  = next

    val custom_beq = next
    val custom_bne = next

    // csr / exception
    val sel_csr = newSel

    val csrrd   = next
    val csrwr   = next
    val csrxchg = next
    val break_  = next
    val syscall = next
    val ertn    = next
    val idle    = next

    // load / store without csr
    val sel_simpleMemory = newSel

    val ld_b  = next // 000
    val ld_bu = next // 001
    val ld_h  = next // 010
    val ld_hu = next // 011
    val ld_w  = next // 100
    val st_b  = next // 101
    val st_h  = next
    val st_w  = next

    // maintainance + atomic load / store
    val sel_complexMemory = newSel

    val ll      = next
    val sc      = next
    val tlbsrch = next
    val tlbrd   = next
    val tlbwr   = next
    val tlbfill = next
    val invtlb  = next
    val cacop   = next

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
    val st_b  = next // 1F
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
    val ertn    = next // 2E
    val idle    = next

    // stable counter
    val rdcntvl_w = next
    val rdcntvh_w = next
    val rdcntid   = next

    // TLB
    val tlbsrch = next
    val tlbrd   = next
    val tlbwr   = next
    val tlbfill = next
    val invtlb  = next

    val cacop = next

    object Tlb {
      val clrAll          = 0.U(spec.Width.Tlb.op)
      val clrAllAlt       = 1.U(spec.Width.Tlb.op)
      val clrGlobl        = 2.U(spec.Width.Tlb.op)
      val clrNGlobl       = 3.U(spec.Width.Tlb.op)
      val clrNGloblAsId   = 4.U(spec.Width.Tlb.op)
      val clrNGloblAsIdVa = 5.U(spec.Width.Tlb.op)
      val clrGloblAsIdVa  = 6.U(spec.Width.Tlb.op)
    }
  }
}
