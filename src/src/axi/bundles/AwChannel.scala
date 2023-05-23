package axi.bundles

import chisel3._
import chisel3.util._

class AwChannel
    extends DecoupledIO(new Bundle {
      val id     = UInt(4.W)
      val addr   = UInt(spec.Width.Axi.addr)
      val len    = UInt(8.W)
      val size   = UInt(3.W)
      val burst  = UInt(2.W)
      val lock   = Bool()
      val cache  = UInt(4.W)
      val prot   = UInt(3.W)
      val qos    = UInt(4.W)
      val region = UInt(4.W)
      val user   = UInt(spec.Param.Width.Axi.user)
    })
