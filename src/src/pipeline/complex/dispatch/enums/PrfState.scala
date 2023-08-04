package pipeline.complex.dispatch.enums

import chisel3.ChiselEnum

object PrfState extends ChiselEnum {
  val free, busy, retire = Value
}
