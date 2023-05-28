package memory.enums

import chisel3.ChiselEnum

object ICacheState extends ChiselEnum {
  val ready, refillForRead, invalid = Value
}
// ready: avaliable
//refillForRead: refill instrution that missed
