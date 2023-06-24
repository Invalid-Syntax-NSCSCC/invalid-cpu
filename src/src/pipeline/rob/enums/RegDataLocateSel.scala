package pipeline.rob.enums

import chisel3.ChiselEnum

object RegDataLocateSel extends ChiselEnum {
  val regfile, rob = Value
}
