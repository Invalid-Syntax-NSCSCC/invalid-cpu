package axi.bundles

import chisel3._
import chisel3.util._
import spec.Param

class RChannel
    extends DecoupledIO(new Bundle {
      val id   = UInt(4.W)
      val data = UInt(Param.Width.Axi.data)
      val resp = UInt(2.W)
      val last = Bool()
      val user = UInt(Param.Width.Axi.ruser)
    })
