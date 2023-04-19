package pipeline.mem.enums

import chisel3.ChiselEnum

object AddrTransMode extends ChiselEnum {
  val direct, directMapping, pageTableMapping = Value
}
