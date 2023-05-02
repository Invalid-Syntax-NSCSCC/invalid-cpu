package memory.enums

import chisel3.ChiselEnum

object TlbMemType extends ChiselEnum {
  val fetch, load, store = Value
}
