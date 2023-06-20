package control.enums

import chisel3._
import chisel3.util._

object ExceptionPos extends ChiselEnum {
  val none, frontend, backend = Value
}
