package pipeline.dispatch.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import spec._

class InstInfoBundle extends Bundle {
  val pcAddr = UInt(Width.Reg.data)
  val inst   = UInt(Width.inst)
}

object InstInfoBundle {
  val default = (new InstInfoBundle).Lit(
    _.pcAddr -> zeroWord,
    _.inst -> zeroWord
  )
}
