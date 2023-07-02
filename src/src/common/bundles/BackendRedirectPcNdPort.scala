package common.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._

class BackendRedirectPcNdPort extends Bundle {
  val en     = Bool()
  val pcAddr = UInt(Width.Reg.data)
  val ftqId  = UInt(Param.BPU.Width.id)
}

object BackendRedirectPcNdPort {
  def default = (new BackendRedirectPcNdPort).Lit(
    _.en -> false.B,
    _.pcAddr -> zeroWord,
    _.ftqId -> 0.U
  )
}
