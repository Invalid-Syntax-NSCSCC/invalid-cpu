package pipeline.common.bundles

import chisel3._
import spec._

class PcInstBundle extends Bundle {
  val pcAddr = UInt(Width.Reg.data)
  val inst   = UInt(Width.Reg.data)
}
