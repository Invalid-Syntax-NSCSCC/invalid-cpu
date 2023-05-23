package spec

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import control.bundles.EcodeBundle

object Csr {
  object Index {
    private var count = 0
    private def h(str: String): UInt = {
      count += 1
      ("h" + str).U(spec.`package`.Width.Csr.addr)
    }

    val crmd      = h("0")
    val prmd      = h("1")
    val euen      = h("2")
    val ecfg      = h("4")
    val estat     = h("5")
    val era       = h("6")
    val badv      = h("7")
    val eentry    = h("c")
    val tlbidx    = h("10")
    val tlbehi    = h("11")
    val tlbelo0   = h("12")
    val tlbelo1   = h("13")
    val asid      = h("18")
    val pgdl      = h("19")
    val pgdh      = h("1a")
    val pgd       = h("1b")
    val cpuid     = h("20")
    val save0     = h("30")
    val save1     = h("31")
    val save2     = h("32")
    val save3     = h("33")
    val tid       = h("40")
    val tcfg      = h("41")
    val tval      = h("42")
    val ticlr     = h("44")
    val llbctl    = h("60")
    val tlbrentry = h("88")
    val ctag      = h("98")
    val dmw0      = h("180")
    val dmw1      = h("181")

    val addrs = Seq(
      "0",
      "1",
      "2",
      "4",
      "5",
      "6",
      "7",
      "c",
      "10",
      "11",
      "12",
      "13",
      "18",
      "19",
      "1a",
      "1b",
      "20",
      "30",
      "31",
      "32",
      "33",
      "40",
      "41",
      "42",
      "44",
      "60",
      "88",
      "98",
      "180",
      "181"
    ).map { h(_) }
    val num = addrs.length
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
    var count = -1

    private def next = {
      count += 1
      count.U
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
