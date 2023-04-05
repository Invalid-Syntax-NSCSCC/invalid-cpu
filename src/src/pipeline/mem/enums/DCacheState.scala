package pipeline.mem.enums

import chisel3.ChiselEnum

object DCacheState extends ChiselEnum {
  val Ready, Write, FetchForRead, FetchForReadAndWb, FetchForWrite, FetchForWriteAndWb, OnlyWb = Value
}
