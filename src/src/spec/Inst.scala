package spec

import chisel3._
import chisel3.internal.firrtl.Width
import spec.Width.{Op => wd}

object Inst {
  private def b(str: String, width: Width): UInt = {
    assert(str.length.W == width)
    ("b" + str).U(width)
  }

  object _2RI12 {
    private def i(str: String) = b(str, wd._2RI12)
    val addi_w = i("0000001010")
  }
}
