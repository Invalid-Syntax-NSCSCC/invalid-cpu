package pipeline.mem.bundles

import chisel3._
import chisel3.util._
import spec._

class StatusTagBundle extends Bundle {
  val isValid = Bool()
  val isDirty = Bool()
  val tag     = UInt(Param.Width.DCache.tag)
}
