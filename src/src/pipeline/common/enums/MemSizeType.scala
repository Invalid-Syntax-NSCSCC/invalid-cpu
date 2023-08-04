package pipeline.common.enums

import chisel3.ChiselEnum

object MemSizeType extends ChiselEnum {
  val byte, halfWord, word = Value
}