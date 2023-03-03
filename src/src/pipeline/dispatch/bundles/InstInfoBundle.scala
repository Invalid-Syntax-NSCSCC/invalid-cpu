package pipeline.dispatch.bundles

import chisel3._
import chisel3.util._
import spec._

class InstInfoBundle extends Bundle {
  val pcAddr = UInt(Width.Reg.data)
  val inst   = UInt(Width.inst)
}
