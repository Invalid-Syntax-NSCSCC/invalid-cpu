package control.csrBundles

import chisel3._

class PgdBundle extends Bundle {
  val base = UInt(20.W)
  val zero = UInt(12.W)
}

object PgdBundle {
  def default = 0.U.asTypeOf(new PgdBundle)
}
