package axi.bundles

import chisel3._
import chisel3.util._
import spec.Param

class WChannel
    extends DecoupledIO(new Bundle {
      val data = UInt(Param.Width.Axi.data)
      val strb = UInt(4.W)
      val last = Bool()
      val user = UInt(Param.Width.Axi.wuser)
    })
