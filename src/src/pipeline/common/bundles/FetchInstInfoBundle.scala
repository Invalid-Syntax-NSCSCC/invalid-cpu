package pipeline.common.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import spec._
import common.bundles.RfAccessInfoNdPort

class FetchInstInfoBundle extends Bundle {
  val pcAddr         = UInt(Width.Reg.data)
  val inst           = UInt(Width.inst)
  val ftqInfo        = new FtqInfoBundle
  val exceptionValid = Bool()
  val exception      = UInt(Width.Csr.exceptionIndex)
  val customInstInfo = new CustomInstInfoBundle
}

object FetchInstInfoBundle {
  def default = 0.U.asTypeOf(new FetchInstInfoBundle)
}
