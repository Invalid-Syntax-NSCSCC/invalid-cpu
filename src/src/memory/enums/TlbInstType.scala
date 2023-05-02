package memory.enums

import chisel3.ChiselEnum

object TlbInstType extends ChiselEnum {
  val search, read, write, fill, invalid = Value
}
