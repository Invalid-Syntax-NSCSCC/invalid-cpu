package control.enums

import chisel3._

object ExceptionPos extends ChiselEnum {
  val none, frontend, backend = Value
}
