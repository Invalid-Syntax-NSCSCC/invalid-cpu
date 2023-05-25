package axi.bundles

import chisel3._
import chisel3.util._
import spec.Param

class BChannel
    extends DecoupledIO(new Bundle {
      val id   = UInt(4.W)
      val resp = UInt(2.W)
      val user = UInt(Param.Width.Axi.buser)
    })
