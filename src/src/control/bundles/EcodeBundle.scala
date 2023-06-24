package control.bundles

import chisel3._
import spec._

class EcodeBundle extends Bundle {
  val esubcode = UInt(Csr.Estat.Width.esubcode)
  val ecode    = UInt(Csr.Estat.Width.ecode)
}
