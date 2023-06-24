package control.csrRegsBundles

import chisel3._

class PgdhBundle extends Bundle {
  val base = UInt(20.W)
  val zero = UInt(12.W)
}

object PgdhBundle {
  def default = 0.U.asTypeOf(new PgdhBundle)
}
