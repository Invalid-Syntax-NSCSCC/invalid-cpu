package axi.bundles

import chisel3._
import chisel3.util._
import spec._

class SlaveWrite extends Bundle {
  val aw = Flipped(Decoupled(new Bundle {
    val id    = UInt(Param.Width.Axi.masterId)
    val addr  = UInt(spec.Width.Axi.addr)
    val len   = UInt(8.W)
    val size  = UInt(3.W)
    val burst = UInt(2.W)
    val lock  = Bool()
    val cache = UInt(4.W)
    val prot  = UInt(3.W)
    val qos   = UInt(4.W)
    val user  = UInt(Param.Width.Axi.awuser)
  }))
  val w = Flipped(Decoupled(new Bundle {
    val data = UInt(Param.Width.Axi.data)
    val strb = UInt(Param.Width.Axi.strb)
    val last = Bool()
    val user = UInt(Param.Width.Axi.wuser)
  }))
  val b = Decoupled(new Bundle {
    val id   = UInt(Param.Width.Axi.masterId)
    val resp = UInt(2.W)
    val user = UInt(Param.Width.Axi.buser)
  })
}
