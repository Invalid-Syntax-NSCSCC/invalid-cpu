package spec

import chisel3._
import chisel3.internal.firrtl.Width
import spec.Width.{Op => wd}
import ujson.Str

object Inst {
  private def b(str: String, width: Width): UInt = {
    assert(str.length.W == width)
    ("b" + str).U(width)
  }

  object  _2R {
    private def i(str: String) = b(str, wd._2R)
  }

  object  _3R {
    private def i(str: String) = b(str, wd._3R)
    val add_w = i("0000_0000_0001_0000_0")
    val sub_w = i("0000_0000_0001_0001_0")
    val slt_w = i("0000_0000_0001_0010_0")
    val sltu_w= i("0000_0000_0001_0010_1")
    val nor_w = i("0000_0000_0001_0100_0")
    val and_w = i("0000_0000_0001_0100_1")
    val  or_w = i("0000_0000_0001_0101_0")
    val xor_w = i("0000_0000_0001_0101_1")
    val sll_w = i("0000_0000_0001_0111_0")
    val srl_w = i("0000_0000_0001_0111_1")
    val sra_w = i("0000_0000_0001_1000_0")
    val mul_w = i("0000_0000_0001_1100_0")
    val mulh_w  = i("0000_0000_0001_1100_1")
    val mulh_wu = i("0000_0000_0001_1101_0")
    val div_w  = i("0000_0000_0010_0000_0")
    val mod_w  = i("0000_0000_0010_0000_1")
    val div_wu = i("0000_0000_0010_0001_0")
    val mod_wu = i("0000_0000_0010_0001_1")
  }

  object  _4R {
    private def i(str: String) = b(str, wd._4R)
  }

  object _2RI12 {
    private def i(str: String) = b(str, wd._2RI12)
    val addi_w = i("0000001010")
  }
}
