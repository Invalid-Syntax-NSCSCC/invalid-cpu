package memory.bundles

import chisel3._
import spec._

class StatusTagBundle extends Bundle {
  val isValid = Bool()
  val isDirty = Bool()
  val tag     = UInt(Param.Width.DCache.tag)
}

object StatusTagBundle {
  val width = 2 + Param.Width.DCache._tag
}
