package spec

import chisel3._
import chisel3.internal.firrtl.Width
import spec.Width.{Op => wd}
import ujson.Str

object Inst {
  private def b(str: String, width: Width, underLineNum: Int): UInt = {
    assert((str.length - underLineNum).W == width)
    ("b" + str).U(width)
  }

  object _2R {
    private def i(str: String) = b(str, wd._2R, 0)
  }

  object _3R {
    private def i(str: String) = b(str, wd._3R, 4)
    val add_w   = i("0000_0000_0001_0000_0")
    val sub_w   = i("0000_0000_0001_0001_0")
    val slt_w   = i("0000_0000_0001_0010_0")
    val sltu_w  = i("0000_0000_0001_0010_1")
    val nor_w   = i("0000_0000_0001_0100_0")
    val and_w   = i("0000_0000_0001_0100_1")
    val or_w    = i("0000_0000_0001_0101_0")
    val xor_w   = i("0000_0000_0001_0101_1")
    val sll_w   = i("0000_0000_0001_0111_0")
    val srl_w   = i("0000_0000_0001_0111_1")
    val sra_w   = i("0000_0000_0001_1000_0")
    val mul_w   = i("0000_0000_0001_1100_0")
    val mulh_w  = i("0000_0000_0001_1100_1")
    val mulh_wu = i("0000_0000_0001_1101_0")
    val div_w   = i("0000_0000_0010_0000_0")
    val mod_w   = i("0000_0000_0010_0000_1")
    val div_wu  = i("0000_0000_0010_0001_0")
    val mod_wu  = i("0000_0000_0010_0001_1")
    val slli_w  = i("0000_0000_0100_0000_1")
    val srli_w  = i("0000_0000_0100_0100_1")
    val srai_w  = i("0000_0000_0100_1000_1")
  }

  object _4R {
    private def i(str: String) = b(str, wd._4R, 0)
  }

  object _2RI12 {
    private def i(str: String) = b(str, wd._2RI12, 2)
    val slti   = i("0000_0010_00")
    val sltui  = i("0000_0010_01")
    val addi_w = i("0000_0010_10")
    val andi   = i("0000_0011_01")
    val ori    = i("0000_0011_10")
    val xori   = i("0000_0011_11")

    val ld_b  = i("0010_1000_00")
    val ld_h  = i("0010_1000_01")
    val ld_w  = i("0010_1000_10")
    val st_b  = i("0010_1001_00")
    val st_h  = i("0010_1001_01")
    val st_w  = i("0010_1001_10")
    val ld_bu = i("0010_1010_00")
    val ld_hu = i("0010_1010_01")
  }

  object _2RI14 {
    private def i(str: String) = b(str, wd._2RI14, 1)
    val ll = i("0010_0000")
    val sc = i("0010_0001")
  }

  object _2RI16 {
    private def i(str: String) = b(str, wd._2RI16, 1)
    // val bceqz = i("010_010")
    // val bcnez = i("010_010")
    val jirl = i("010_011")
    val b_   = i("010_100")
    val bl   = i("010_101")
    val beq  = i("010_110")
    val bne  = i("010_111")
    val blt  = i("011_000")
    val bge  = i("011_001")
    val bltu = i("011_010")
    val bgeu = i("011_011")
  }

  object _special {
    private def i(str: String) = b(str, wd._2RI16, 1)
    val lu12i_w   = i("0001_010")
    val pcaddu12i = i("0001_110")
  }
}
