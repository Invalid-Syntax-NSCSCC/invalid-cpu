package memory.bundles

import chisel3._
import spec._

class ICacheStatusTagBundle extends Bundle {
  val isValid = Bool()
  val tag     = UInt(Param.Width.ICache.tag)
}

object ICacheStatusTagBundle {
  val width = 1 + Param.Width.ICache._tag
}
