package spec

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import control.bundles.EcodeBundle

object CsrRegs {

  object Index {
    private var count = 0

    private def next = {
      count += 1
      count.U(Width.CsrReg.addr)
    }

    val crmd      = next
    val prmd      = next
    val euen      = next
    val ecfg      = next
    val estat     = next
    val era       = next
    val badv      = next
    val eentry    = next
    val tlbidx    = next
    val tlbehi    = next
    val tlbelo0   = next
    val tlbelo1   = next
    val asid      = next
    val pgdl      = next
    val pgdh      = next
    val pgd       = next
    val cpuid     = next
    val save0     = next
    val save1     = next
    val save2     = next
    val save3     = next
    val tid       = next
    val tcfg      = next
    val tval      = next
    val ticlr     = next
    val llbctl    = next
    val tlbrentry = next
    val ctag      = next
    val dmw0      = next
    val dmw1      = next

    def getCount: Int = count + 1
  }

  object Estat {
    object Width {
      val ecode    = 6.W
      val esubcode = 9.W
    }

    def e(ecode: String, esubcode: Int = 0) = (new EcodeBundle).Lit(
      _.ecode -> ("h" + ecode).U(Width.ecode),
      _.esubcode -> esubcode.U(Width.esubcode)
    )

    val int  = e("0")
    val pil  = e("1")
    val pis  = e("2")
    val pif  = e("3")
    val pme  = e("4")
    val ppi  = e("7")
    val adef = e("8", 0)
    val adem = e("8", 1)
    val ale  = e("9")
    val sys  = e("b")
    val brk  = e("c")
    val ine  = e("d")
    val ipe  = e("e")
    val fpd  = e("f")
    val fpe  = e("12", 0)
    val tlbr = e("3f")

  }

  object TimeVal {
    object Width {
      // 待修改
      val timeVal = 16
    }
  }

  object Tlbidx {
    object Width {
      // Attention: 这与TLB实现有关，待修改
      val index = 12
    }
  }

  object ExceptionIndex {
    private var count = -1

    private def next = {
      count += 1
      count.U(Width.CsrReg.addr)
    }

    val int  = next
    val pil  = next
    val pis  = next
    val pif  = next
    val pme  = next
    val ppi  = next
    val adef = next
    val adem = next
    val ale  = next
    val sys  = next
    val brk  = next
    val ine  = next
    val ipe  = next
    val fpd  = next
    val fpe  = next
    val tlbr = next

    def width = count + 1
  }
}
