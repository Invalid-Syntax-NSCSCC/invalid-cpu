package frontend.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class ICacheRequestNdPort extends Bundle {
  val isValid = Bool()
  val addr    = UInt(Width.Mem.addr)
  // when muti fetch,mem addr must ignore addr of fetchWidth tag;  mem data index (log(fetchNum) bits) default to 0
  val isCached = Bool()
}

object ICacheRequestNdPort {
  def default = (new ICacheRequestNdPort).Lit(
    _.isValid -> false.B,
    _.addr -> zeroWord,
    _.isCached -> false.B
  )
}
