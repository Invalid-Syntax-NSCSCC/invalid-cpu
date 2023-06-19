package spec

import chisel3._
import chisel3.experimental.BundleLiterals._
import control.bundles.EcodeBundle
import spec._
import chisel3.util.log2Ceil

object Csr {

  private def h(s: String): UInt = {
    ("h" + s).U(spec.Width.Csr.addr)
  }

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
  ).map(h)

  object Index {
    var count = -1

    private def next = {
      count += 1
      count.U(Width.Csr.addr)
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
    val pgd       = next // f ->
    val cpuid     = next
    val save0     = next
    val save1     = next
    val save2     = next
    val save3     = next
    val tid       = next
    val tcfg      = next
    val tval      = next
    val ticlr     = next // 0x18 -> 0x44
    val llbctl    = next
    val tlbrentry = next
    val ctag      = next
    val dmw0      = next
    val dmw1      = next

    assert(count + 1 == addrs.length)
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
      val timeVal = 32
    }
  }

  object Tlbidx {
    object Width {
      val index = log2Ceil(Param.Count.Tlb.num)
    }
  }

  object Tlbelo {
    object Width {
      val palen = spec.Width.Mem._addr
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

    def num   = count + 1
    def width = log2Ceil(count + 1).W
  }
}
