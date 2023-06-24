package control.csrBundles

import chisel3._

class PgdlBundle extends Bundle {
  val base = UInt(20.W)
  val zero = UInt(12.W)
}

object PgdlBundle {
  def default = 0.U.asTypeOf(new PgdlBundle)
}
