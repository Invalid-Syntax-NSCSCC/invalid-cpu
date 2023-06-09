package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import spec._

class InstInfoBundle extends Bundle {
  val pcAddr = UInt(Width.Reg.data)
  val inst   = UInt(Width.inst)
  val isAdef = Bool()
  val isPpi  = Bool()
  val isPif  = Bool()
  val isTlbr = Bool()
}

object InstInfoBundle {
  val default = (new InstInfoBundle).Lit(
    _.pcAddr -> zeroWord,
    _.inst -> zeroWord,
    _.isAdef -> false.B,
    _.isPif -> false.B,
    _.isPpi -> false.B,
    _.isTlbr -> false.B
  )
}
