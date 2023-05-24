package pipeline.mem.enums

import chisel3.ChiselEnum

object AddrTransType extends ChiselEnum {
  val direct, directMapping, pageTableMapping = Value
}
