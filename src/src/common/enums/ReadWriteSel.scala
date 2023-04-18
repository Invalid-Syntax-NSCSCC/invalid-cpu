package common.enums

import chisel3.ChiselEnum

object ReadWriteSel extends ChiselEnum {
  val read, write = Value
}
