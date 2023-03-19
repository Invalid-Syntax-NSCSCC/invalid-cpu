package axi.bundles

import chisel3._
import chisel3.util._
import spec._

class SlaveWrite extends Bundle {
  val aw = Flipped(Decoupled(new Bundle {
    val id    = UInt(Param.Width.Axi.masterId.W)
    val addr  = UInt(Width.Axi.addr.W)
    val len   = UInt(8.W)
    val size  = UInt(3.W)
    val burst = UInt(2.W)
    val lock  = Bool()
    val cache = UInt(4.W)
    val prot  = UInt(3.W)
    val qos   = UInt(4.W)
    val user  = UInt(Width.Axi.awuser.W)
  }))
  val w = Flipped(Decoupled(new Bundle {
    val data = UInt(Width.Axi.data.W)
    val strb = UInt(Width.Axi.strb.W)
    val last = Bool()
    val user = UInt(Width.Axi.wuser.W)
  }))
  val b = Decoupled(new Bundle {
    val id   = UInt(Param.Width.Axi.masterId.W)
    val resp = UInt(2.W)
    val user = UInt(Width.Axi.buser.W)
  })
}
