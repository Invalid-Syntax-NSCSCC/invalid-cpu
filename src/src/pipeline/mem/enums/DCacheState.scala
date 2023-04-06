package pipeline.mem.enums

import chisel3.ChiselEnum

object DCacheState extends ChiselEnum {
  val ready, write, refillForRead, refillForReadAndWb, refillForWrite, refillForWriteAndWb, onlyWb = Value
}
