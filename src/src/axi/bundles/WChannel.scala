package axi.bundles

import chisel3._
import chisel3.util._

class WChannel
    extends DecoupledIO(new Bundle {
      val id   = UInt(4.W)
      val data = UInt(spec.Param.Width.Axi.data)
      val strb = UInt(4.W)
      val last = Bool()
      val user = UInt(spec.Param.Width.Axi.user)
    })
